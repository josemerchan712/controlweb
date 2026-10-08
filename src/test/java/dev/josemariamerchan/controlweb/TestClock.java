package dev.josemariamerchan.controlweb;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The {@link Clock} of the integration tests (registered as primary in {@link TestcontainersConfiguration}).
 * It follows the real time until a test calls {@link #fix(Instant)}; tests that do must call {@link #reset()}
 * afterwards. Fix it to a moment in the past: access tokens are validated against this clock too.
 */
public final class TestClock extends Clock {

	private volatile Instant fixed;

	public void fix(Instant instant) {
		this.fixed = instant;
	}

	public void reset() {
		this.fixed = null;
	}

	@Override
	public Instant instant() {
		Instant current = fixed;
		return current != null ? current : Instant.now();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}
}
