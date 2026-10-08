package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.alert.service.AlertRateLimitedException;
import dev.josemariamerchan.controlweb.alert.service.AlertTestRateLimiter;
import dev.josemariamerchan.controlweb.config.AlertProperties;

class AlertTestRateLimiterTest {

	/** A clock the test can move. */
	private static final class MovableClock extends Clock {

		private Instant now = Instant.parse("2026-03-01T12:00:00Z");

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
	private final AlertTestRateLimiter limiter = new AlertTestRateLimiter(clock,
			new AlertProperties(100, Duration.ofSeconds(10), Duration.ofSeconds(10)));

	@Test
	@DisplayName("the first call is allowed, a second one within 10 seconds is not")
	void secondCallTooSoon() {
		assertThatCode(limiter::acquire).doesNotThrowAnyException();

		clock.advance(Duration.ofSeconds(3));

		assertThatThrownBy(limiter::acquire).isInstanceOf(AlertRateLimitedException.class)
				.satisfies(ex -> assertThat(((AlertRateLimitedException) ex).getRetryAfterSeconds()).isEqualTo(7));
	}

	@Test
	@DisplayName("exactly 10 seconds later it is allowed again")
	void allowedAgainAfterTheInterval() {
		limiter.acquire();

		clock.advance(Duration.ofSeconds(10));

		assertThatCode(limiter::acquire).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a refused call does not extend the wait")
	void refusedCallsDoNotPushTheWindow() {
		limiter.acquire();
		clock.advance(Duration.ofSeconds(9));
		assertThatThrownBy(limiter::acquire).isInstanceOf(AlertRateLimitedException.class);

		clock.advance(Duration.ofSeconds(1));

		assertThatCode(limiter::acquire).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the retry hint rounds up and is at least one second")
	void retryAfterRoundsUp() {
		limiter.acquire();
		clock.advance(Duration.ofMillis(9_500));

		assertThatThrownBy(limiter::acquire)
				.satisfies(ex -> assertThat(((AlertRateLimitedException) ex).getRetryAfterSeconds()).isEqualTo(1));
	}

	@Test
	@DisplayName("many simultaneous callers: exactly one gets through")
	void concurrentCallersOnlyOneWins() throws Exception {
		int callers = 16;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		CompletableFuture<?>[] futures = new CompletableFuture<?>[callers];
		for (int i = 0; i < callers; i++) {
			futures[i] = CompletableFuture.runAsync(() -> {
				try {
					start.await();
					limiter.acquire();
					allowed.incrementAndGet();
				}
				catch (AlertRateLimitedException ex) {
					refused.incrementAndGet();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});
		}
		start.countDown();
		CompletableFuture.allOf(futures).get();

		assertThat(allowed.get()).isEqualTo(1);
		assertThat(refused.get()).isEqualTo(callers - 1);
	}

	@Test
	@DisplayName("reset (for tests) forgets the previous call")
	void reset() {
		limiter.acquire();
		limiter.reset();

		assertThatCode(limiter::acquire).doesNotThrowAnyException();
	}
}
