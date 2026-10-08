package dev.josemariamerchan.controlweb.publicstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.config.PublicProperties;

/** The cache protects the database: at most one read per TTL, one reload at a time, and it survives a failing reload. */
class PublicStatusCacheTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	private static final class MovableClock extends Clock {

		private volatile Instant now = T0;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final MovableClock clock = new MovableClock();
	private final PublicStatusLoader loader = mock(PublicStatusLoader.class);

	private PublicStatusCache cache(Duration ttl) {
		return new PublicStatusCache(loader, clock, new PublicProperties(List.of(), ttl, Duration.ofSeconds(30)));
	}

	private PublicStatusResponse response(String marker) {
		return new PublicStatusResponse(PublicState.UP, clock.instant(), List.of(new PublicSiteView(marker, PublicState.UP, null)));
	}

	@Test
	@DisplayName("many requests inside the TTL read the database once and get the very same answer")
	void loadsOnceWithinTheTtl() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		PublicStatusResponse first = response("first");
		when(loader.load()).thenReturn(first);

		for (int i = 0; i < 50; i++) {
			assertThat(cache.get()).isSameAs(first);
			clock.advance(Duration.ofMillis(200)); // 10 s in total
		}

		verify(loader, times(1)).load();
	}

	@Test
	@DisplayName("the answer is reloaded exactly when the TTL is over (not before), and then reused again")
	void reloadsAfterTheTtl() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		PublicStatusResponse first = response("first");
		PublicStatusResponse second = response("second");
		when(loader.load()).thenReturn(first, second);

		assertThat(cache.get()).isSameAs(first);
		clock.advance(Duration.ofSeconds(15).minusNanos(1));
		assertThat(cache.get()).as("1 ns before the TTL ends").isSameAs(first);
		clock.advance(Duration.ofNanos(1));
		assertThat(cache.get()).as("exactly at the TTL").isSameAs(second);
		clock.advance(Duration.ofSeconds(14));
		assertThat(cache.get()).isSameAs(second);

		verify(loader, times(2)).load();
	}

	@Test
	@DisplayName("a TTL of zero disables the cache: every request reads the database")
	void zeroTtl() {
		PublicStatusCache cache = cache(Duration.ZERO);
		when(loader.load()).thenReturn(response("x"));

		cache.get();
		cache.get();
		cache.get();

		verify(loader, times(3)).load();
	}

	@Test
	@DisplayName("invalidate forgets the stored answer")
	void invalidate() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		when(loader.load()).thenReturn(response("x"));
		cache.get();

		cache.invalidate();
		cache.get();

		verify(loader, times(2)).load();
	}

	// ---- single flight ----

	@Test
	@DisplayName("a crowd of simultaneous requests on a cold cache causes ONE slow reload; everyone gets its result")
	void singleFlightOnColdCache() throws Exception {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		AtomicInteger loads = new AtomicInteger();
		PublicStatusResponse loaded = response("loaded");
		when(loader.load()).thenAnswer(invocation -> {
			loads.incrementAndGet();
			Thread.sleep(300); // slow enough for everybody to pile up behind it
			return loaded;
		});

		Set<PublicStatusResponse> answers = callConcurrently(cache, 40);

		assertThat(loads.get()).as("reloads").isEqualTo(1);
		assertThat(answers).containsExactly(loaded);
	}

	@Test
	@DisplayName("the same when the stored answer has just expired")
	void singleFlightOnExpiredCache() throws Exception {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		AtomicInteger loads = new AtomicInteger();
		PublicStatusResponse old = response("old");
		PublicStatusResponse fresh = response("fresh");
		when(loader.load()).thenAnswer(invocation -> {
			if (loads.incrementAndGet() == 1) {
				return old;
			}
			Thread.sleep(300);
			return fresh;
		});
		cache.get();
		clock.advance(Duration.ofSeconds(16));

		Set<PublicStatusResponse> answers = callConcurrently(cache, 40);

		assertThat(loads.get()).as("1 initial load + 1 reload").isEqualTo(2);
		assertThat(answers).containsExactly(fresh);
	}

	private Set<PublicStatusResponse> callConcurrently(PublicStatusCache cache, int callers) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<CompletableFuture<PublicStatusResponse>> futures = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				futures.add(CompletableFuture.supplyAsync(() -> {
					try {
						go.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					return cache.get();
				}, pool));
			}
			go.countDown();
			Set<PublicStatusResponse> answers = new HashSet<>();
			for (CompletableFuture<PublicStatusResponse> future : futures) {
				answers.add(future.get(10, TimeUnit.SECONDS));
			}
			return answers;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- failures ----

	@Test
	@DisplayName("with nothing stored, a failing load is a PublicStatusUnavailableException with no internals, and the next request retries")
	void failureWithoutStaleAnswer() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		PublicStatusResponse recovered = response("recovered");
		when(loader.load()).thenThrow(new IllegalStateException("jdbc:postgresql://db:5432/controlweb password=hunter2"))
				.thenReturn(recovered);

		assertThatThrownBy(cache::get).isInstanceOf(PublicStatusUnavailableException.class)
				.hasMessage("The status is temporarily unavailable").hasNoCause();
		assertThat(cache.get()).isSameAs(recovered);
	}

	@Test
	@DisplayName("when a reload fails, the previous answer keeps being served (its generatedAt shows its age)")
	void staleOnError() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		PublicStatusResponse good = response("good");
		when(loader.load()).thenReturn(good).thenThrow(new IllegalStateException("database down"));
		cache.get();

		clock.advance(Duration.ofSeconds(20));

		assertThat(cache.get()).isSameAs(good);
		assertThat(good.generatedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("but a stale answer is not served forever: past 10 TTLs the failure is reported")
	void staleAnswerExpires() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		when(loader.load()).thenReturn(response("good")).thenThrow(new IllegalStateException("database down"));
		cache.get();

		clock.advance(Duration.ofSeconds(150)); // exactly 10 TTLs: still acceptable
		assertThat(cache.get().sites().get(0).name()).isEqualTo("good");
		clock.advance(Duration.ofSeconds(1));

		assertThatThrownBy(cache::get).isInstanceOf(PublicStatusUnavailableException.class);
	}

	@Test
	@DisplayName("a successful reload after an outage replaces the stale answer")
	void recoversAfterOutage() {
		PublicStatusCache cache = cache(Duration.ofSeconds(15));
		PublicStatusResponse recovered = response("recovered");
		when(loader.load()).thenReturn(response("good")).thenThrow(new IllegalStateException("down")).thenReturn(recovered);
		cache.get();
		clock.advance(Duration.ofSeconds(20));
		cache.get(); // stale, served because the reload failed

		clock.advance(Duration.ofSeconds(1));

		assertThat(cache.get()).isSameAs(recovered);
	}
}
