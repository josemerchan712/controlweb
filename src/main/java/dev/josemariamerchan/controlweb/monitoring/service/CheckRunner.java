package dev.josemariamerchan.controlweb.monitoring.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.SchedulerProperties;
import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;
import jakarta.annotation.PreDestroy;

/**
 * Runs checks: the periodic cycle ({@link #runCycle()}) and the manual check
 * ({@link #runManually(Long)}), both protected by the same {@link InFlightGuard}.
 *
 * <p>The cycle runs each due site on its own virtual thread, but at most {@code max-concurrency} at
 * a time (a semaphore), and returns only when all of them have finished, so with a fixed delay a
 * slow cycle simply delays the next one. A failure on one site never affects the others.
 * Manual checks are user-initiated one-offs and are not counted against the concurrency limit.
 */
@Component
public class CheckRunner {

	private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);

	private final CheckService checkService;
	private final InFlightGuard guard;
	private final SchedulerProperties properties;
	private final Semaphore permits;
	private final ExecutorService executor = Executors
			.newThreadPerTaskExecutor(Thread.ofVirtual().name("site-check-", 0).factory());
	private final AtomicBoolean accepting = new AtomicBoolean(true);

	public CheckRunner(CheckService checkService, InFlightGuard guard, SchedulerProperties properties) {
		this.checkService = checkService;
		this.guard = guard;
		this.properties = properties;
		this.permits = new Semaphore(properties.maxConcurrency());
	}

	/** Checks every site that is due and waits for all of those checks to finish. Never throws. */
	public void runCycle() {
		if (!accepting.get()) {
			return;
		}
		List<Long> due;
		try {
			due = checkService.findDueSiteIds();
		}
		catch (RuntimeException ex) {
			logFailure("Could not load the sites that are due", ex);
			return;
		}

		List<Future<?>> running = new ArrayList<>();
		for (Long siteId : due) {
			if (!guard.tryAcquire(siteId)) {
				log.debug("Skipping site {}: a check is already in progress", siteId);
				continue;
			}
			try {
				running.add(executor.submit(() -> checkGuarded(siteId)));
			}
			catch (RejectedExecutionException ex) {
				guard.release(siteId); // shutting down: accept no more work
				break;
			}
		}
		awaitAll(running);
	}

	/**
	 * Checks one site right now.
	 *
	 * @throws CheckInProgressException if that site is already being checked
	 * @throws SiteNotFoundException    if the site does not exist
	 */
	public CheckResponse runManually(Long siteId) {
		if (!guard.tryAcquire(siteId)) {
			throw new CheckInProgressException(siteId);
		}
		try {
			return checkService.runCheck(siteId);
		}
		finally {
			guard.release(siteId);
		}
	}

	/** Runs on a virtual thread; the caller already owns the guard for this site. */
	private void checkGuarded(Long siteId) {
		try {
			permits.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			guard.release(siteId);
			return;
		}
		try {
			checkService.runCheck(siteId);
		}
		catch (SiteNotFoundException ex) {
			log.debug("Site {} was deleted before its check finished", siteId);
		}
		catch (RuntimeException ex) {
			logFailure("Check of site " + siteId + " failed unexpectedly", ex);
		}
		finally {
			permits.release();
			guard.release(siteId);
		}
	}

	private static void awaitAll(List<Future<?>> running) {
		for (Future<?> future : running) {
			try {
				future.get();
			}
			catch (ExecutionException ex) {
				// checkGuarded already logged it
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				log.info("Check cycle interrupted while waiting for running checks");
				return;
			}
		}
	}

	/** Only the exception type and a sanitized message are logged: they could otherwise contain a URL with a token. */
	private static void logFailure(String what, RuntimeException ex) {
		log.warn("{}: {} {}", what, ex.getClass().getName(), MessageSanitizer.sanitize(ex.getMessage()));
	}

	/**
	 * Clean stop: no new checks are accepted, the running ones get {@code shutdown-timeout} to finish,
	 * and whatever is still running after that is interrupted.
	 */
	@PreDestroy
	public void shutdown() {
		accepting.set(false);
		executor.shutdown();
		try {
			if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				List<Runnable> neverStarted = executor.shutdownNow();
				log.warn("Shutdown timeout reached: interrupted the running site checks ({} had not started)",
						neverStarted.size());
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
