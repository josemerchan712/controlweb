package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.josemariamerchan.controlweb.config.SchedulerProperties;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.monitoring.service.CheckInProgressException;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;
import dev.josemariamerchan.controlweb.monitoring.service.CheckService;
import dev.josemariamerchan.controlweb.monitoring.service.InFlightGuard;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/** The runner's concurrency rules, with a fake CheckService so no network or database is involved. */
class CheckRunnerTest {

	private final CheckService service = mock(CheckService.class);
	private final InFlightGuard guard = new InFlightGuard();
	private final List<CheckRunner> runners = new ArrayList<>();
	private ListAppender<ILoggingEvent> logs;
	private Logger runnerLogger;
	private Level previousLevel;

	@BeforeEach
	void captureLogs() {
		runnerLogger = (Logger) LoggerFactory.getLogger(CheckRunner.class);
		previousLevel = runnerLogger.getLevel();
		runnerLogger.setLevel(Level.DEBUG);
		logs = new ListAppender<>();
		logs.start();
		runnerLogger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		runnerLogger.detachAppender(logs);
		runnerLogger.setLevel(previousLevel);
		runners.forEach(CheckRunner::shutdown);
	}

	private CheckRunner runner(int maxConcurrency, Duration shutdownTimeout) {
		CheckRunner runner = new CheckRunner(service, guard,
				new SchedulerProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(10), maxConcurrency,
						shutdownTimeout));
		runners.add(runner);
		return runner;
	}

	private CheckRunner runner() {
		return runner(10, Duration.ofSeconds(5));
	}

	private static CheckResponse ok(Long siteId) {
		return new CheckResponse(1L, siteId, null, true, 200, 10, null, null);
	}

	private static List<Long> ids(int count) {
		List<Long> ids = new ArrayList<>();
		for (long i = 1; i <= count; i++) {
			ids.add(i);
		}
		return ids;
	}

	// ---- the cycle ----

	@Test
	@DisplayName("a cycle checks every due site and returns only when all of them have finished")
	void cycleChecksAllDueSitesAndWaits() throws Exception {
		Set<Long> finished = ConcurrentHashMap.newKeySet();
		when(service.findDueSiteIds()).thenReturn(List.of(1L, 2L, 3L));
		when(service.runCheck(anyLong())).thenAnswer(invocation -> {
			Thread.sleep(100);
			Long id = invocation.getArgument(0);
			finished.add(id);
			return ok(id);
		});

		runner().runCycle();

		assertThat(finished).containsExactlyInAnyOrder(1L, 2L, 3L);
		assertThat(guard.size()).as("every site is released").isZero();
	}

	@Test
	@DisplayName("a cycle with nothing due does nothing")
	void emptyCycle() {
		when(service.findDueSiteIds()).thenReturn(List.of());

		runner().runCycle();

		verify(service, never()).runCheck(anyLong());
	}

	// ---- in-flight guard ----

	@Test
	@DisplayName("a site whose check is already running is skipped by the cycle")
	void cycleSkipsSitesInFlight() {
		when(service.findDueSiteIds()).thenReturn(List.of(1L, 2L, 3L));
		when(service.runCheck(anyLong())).thenAnswer(invocation -> ok(invocation.getArgument(0)));
		assertThat(guard.tryAcquire(2L)).isTrue(); // e.g. a manual check of site 2 is running

		runner().runCycle();

		verify(service).runCheck(1L);
		verify(service, never()).runCheck(2L);
		verify(service).runCheck(3L);
		assertThat(guard.isInFlight(2L)).as("the cycle must not release a site it does not own").isTrue();
	}

	@Test
	@DisplayName("while a cycle's check of a site is running, a manual check of it is refused and so is a second cycle")
	void nothingRunsTwiceAtOnce() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(service.findDueSiteIds()).thenReturn(List.of(1L));
		when(service.runCheck(1L)).thenAnswer(invocation -> {
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			return ok(1L);
		});
		CheckRunner runner = runner();
		Thread firstCycle = Thread.ofVirtual().start(runner::runCycle);
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> runner.runManually(1L)).isInstanceOf(CheckInProgressException.class);
		runner.runCycle(); // a second, overlapping cycle must not start another check of site 1

		verify(service, times(1)).runCheck(1L);
		release.countDown();
		firstCycle.join(5000);
		assertThat(guard.isInFlight(1L)).isFalse();
	}

	@Test
	@DisplayName("a manual check is refused with CheckInProgressException while another one runs, then works again")
	void manualChecksDoNotOverlap() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(service.runCheck(1L)).thenAnswer(invocation -> {
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			return ok(1L);
		});
		CheckRunner runner = runner();
		Thread first = Thread.ofVirtual().start(() -> runner.runManually(1L));
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> runner.runManually(1L)).isInstanceOf(CheckInProgressException.class)
				.hasMessage("A check of site 1 is already in progress");
		release.countDown();
		first.join(5000);

		assertThat(runner.runManually(1L).up()).isTrue();
	}

	@Test
	@DisplayName("a manual check returns the service's result and always releases the site, even on failure")
	void manualCheckReleasesTheGuard() {
		when(service.runCheck(1L)).thenReturn(ok(1L));
		when(service.runCheck(2L)).thenThrow(new SiteNotFoundException(2L));
		CheckRunner runner = runner();

		assertThat(runner.runManually(1L).siteId()).isEqualTo(1L);
		assertThatThrownBy(() -> runner.runManually(2L)).isInstanceOf(SiteNotFoundException.class);

		assertThat(guard.size()).isZero();
	}

	// ---- concurrency limit ----

	@Test
	@DisplayName("never more checks at once than max-concurrency, yet really in parallel")
	void concurrencyIsLimited() {
		int limit = 3;
		AtomicInteger running = new AtomicInteger();
		AtomicInteger highWaterMark = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();
		when(service.findDueSiteIds()).thenReturn(ids(15));
		when(service.runCheck(anyLong())).thenAnswer(invocation -> {
			highWaterMark.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				Thread.sleep(60);
			}
			finally {
				running.decrementAndGet();
				completed.incrementAndGet();
			}
			return ok(invocation.getArgument(0));
		});

		runner(limit, Duration.ofSeconds(5)).runCycle();

		assertThat(completed.get()).as("every site is still checked").isEqualTo(15);
		assertThat(highWaterMark.get()).isLessThanOrEqualTo(limit).isGreaterThanOrEqualTo(2);
		assertThat(running.get()).isZero();
	}

	@Test
	@DisplayName("the default limit is 10")
	void defaultConcurrencyIsTen() {
		assertThat(new SchedulerProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1), 10, Duration.ofSeconds(1))
				.maxConcurrency()).isEqualTo(10);
	}

	// ---- isolation of failures ----

	@Test
	@DisplayName("a site that fails, or was deleted, does not stop the others and the cycle does not throw")
	void failuresAreIsolated() {
		Set<Long> checked = ConcurrentHashMap.newKeySet();
		when(service.findDueSiteIds()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
		when(service.runCheck(anyLong())).thenAnswer(invocation -> {
			Long id = invocation.getArgument(0);
			if (id == 2L) {
				throw new IllegalStateException("boom talking to https://example.com/x?token=SECRET123");
			}
			if (id == 3L) {
				throw new SiteNotFoundException(id); // deleted while being checked
			}
			if (id == 4L) {
				throw new UnsupportedOperationException("unexpected");
			}
			checked.add(id);
			return ok(id);
		});

		assertThatCode(() -> runner().runCycle()).doesNotThrowAnyException();

		assertThat(checked).containsExactlyInAnyOrder(1L, 5L);
		assertThat(guard.size()).as("failed sites are released too").isZero();
	}

	@Test
	@DisplayName("failures are logged with the exception type and no query string or token")
	void failuresAreLoggedWithoutSecrets() {
		when(service.findDueSiteIds()).thenReturn(List.of(2L));
		when(service.runCheck(2L)).thenThrow(new IllegalStateException(
				"boom talking to https://example.com/x?token=SECRET123 and https://user:pw@host/y"));

		runner().runCycle();

		List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).anyMatch(m -> m.contains("Check of site 2 failed unexpectedly")
				&& m.contains("IllegalStateException"));
		assertThat(messages).noneMatch(m -> m.contains("SECRET123") || m.contains("token=") || m.contains("pw@"));
		assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
	}

	@Test
	@DisplayName("if the due sites cannot be loaded (e.g. database down) the cycle logs and ends quietly")
	void failureLoadingDueSites() {
		when(service.findDueSiteIds()).thenThrow(new IllegalStateException("database is down"));

		assertThatCode(() -> runner().runCycle()).doesNotThrowAnyException();

		verify(service, never()).runCheck(any());
		assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("Could not load the sites that are due"));
	}

	// ---- clean shutdown ----

	@Test
	@DisplayName("shutdown waits for a running check to finish and then accepts no new work")
	void shutdownWaitsForRunningChecks() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean finished = new AtomicBoolean();
		when(service.findDueSiteIds()).thenReturn(List.of(1L));
		when(service.runCheck(1L)).thenAnswer(invocation -> {
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			finished.set(true);
			return ok(1L);
		});
		CheckRunner runner = runner(10, Duration.ofSeconds(5));
		Thread cycle = Thread.ofVirtual().start(runner::runCycle);
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		Thread shutdown = Thread.ofVirtual().start(runner::shutdown);
		shutdown.join(400);
		assertThat(shutdown.isAlive()).as("shutdown is still waiting for the running check").isTrue();
		assertThat(finished).isFalse();

		release.countDown();
		shutdown.join(5000);
		cycle.join(5000);

		assertThat(shutdown.isAlive()).isFalse();
		assertThat(finished).as("the running check was allowed to finish").isTrue();

		runner.runCycle();
		verify(service, times(1)).runCheck(1L);
		verify(service, times(1)).findDueSiteIds(); // a cycle after shutdown does not even look for work
	}

	@Test
	@DisplayName("checks still running after the shutdown timeout are interrupted, and shutdown returns")
	void shutdownInterruptsStuckChecks() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		when(service.findDueSiteIds()).thenReturn(List.of(1L));
		when(service.runCheck(1L)).thenAnswer(invocation -> {
			started.countDown();
			try {
				Thread.sleep(30_000);
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
				throw ex;
			}
			return ok(1L);
		});
		CheckRunner runner = runner(10, Duration.ofMillis(200));
		Thread cycle = Thread.ofVirtual().start(runner::runCycle);
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		long begin = System.nanoTime();
		runner.shutdown();
		long elapsedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis();
		cycle.join(5000);

		assertThat(elapsedMs).isBetween(150L, 3000L);
		assertThat(interrupted).as("the stuck check was interrupted").isTrue();
		assertThat(cycle.isAlive()).isFalse();
		assertThat(guard.size()).isZero();
		verify(service, never()).runCheck(eq(2L));
	}
}
