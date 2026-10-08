package dev.josemariamerchan.controlweb.auth.ratelimit;

import java.time.Duration;

/**
 * A login attempt was refused because of a rate limit. The message is the same whichever limit was hit and never
 * contains the client address or the email, so it reveals nothing about the account.
 */
public class LoginRateLimitedException extends RuntimeException {

	private final long retryAfterSeconds;

	LoginRateLimitedException(Duration wait) {
		super("Too many login attempts. Try again later.");
		this.retryAfterSeconds = Math.max(1, (wait.toMillis() + 999) / 1000);
	}

	/** Seconds until at least one more attempt is possible (at least 1). */
	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
