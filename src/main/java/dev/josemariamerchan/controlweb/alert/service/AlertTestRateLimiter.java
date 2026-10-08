package dev.josemariamerchan.controlweb.alert.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.AlertProperties;

/**
 * Allows one test alert per interval (10 s by default) so the endpoint cannot be used to spam a chat or to
 * hammer the Telegram API. Every attempt counts, whatever its outcome. Global (not per user) and in memory,
 * which is right for a single instance.
 */
@Component
public class AlertTestRateLimiter {

	private final Clock clock;
	private final Duration interval;
	private final AtomicReference<Instant> lastAllowed = new AtomicReference<>();

	public AlertTestRateLimiter(Clock clock, AlertProperties properties) {
		this.clock = clock;
		this.interval = properties.testInterval();
	}

	/** @throws AlertRateLimitedException if the previous call was less than one interval ago */
	public void acquire() {
		Instant now = clock.instant();
		while (true) {
			Instant previous = lastAllowed.get();
			if (previous != null && now.isBefore(previous.plus(interval))) {
				long remainingMillis = Duration.between(now, previous.plus(interval)).toMillis();
				throw new AlertRateLimitedException(Math.max(1, (remainingMillis + 999) / 1000));
			}
			if (lastAllowed.compareAndSet(previous, now)) {
				return;
			}
		}
	}

	/** Forgets the previous call. Meant for tests. */
	public void reset() {
		lastAllowed.set(null);
	}
}
