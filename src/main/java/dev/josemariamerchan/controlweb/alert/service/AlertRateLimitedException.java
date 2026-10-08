package dev.josemariamerchan.controlweb.alert.service;

/** The test alert was requested again too soon. */
public class AlertRateLimitedException extends RuntimeException {

	private final long retryAfterSeconds;

	public AlertRateLimitedException(long retryAfterSeconds) {
		super("The test alert can be sent once every few seconds; try again in %d s".formatted(retryAfterSeconds));
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
