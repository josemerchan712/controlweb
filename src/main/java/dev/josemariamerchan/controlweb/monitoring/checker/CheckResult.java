package dev.josemariamerchan.controlweb.monitoring.checker;

import java.time.Instant;

/**
 * Outcome of one check of a site, before it is stored.
 *
 * @param up            whether the site answered with a status below 400
 * @param statusCode    final HTTP status, or null if no response was received
 * @param latencyMs     milliseconds until the final response headers arrived (all redirect hops
 *                      included), or null if no response was received
 * @param errorMessage  why the check failed (sanitized, at most 500 characters), null when up
 * @param certExpiresAt expiry of the server certificate when a TLS handshake succeeded, else null
 */
public record CheckResult(boolean up, Integer statusCode, Integer latencyMs, String errorMessage,
		Instant certExpiresAt) {

	public CheckResult {
		errorMessage = MessageSanitizer.sanitize(errorMessage);
	}

	public static CheckResult succeeded(int statusCode, int latencyMs, Instant certExpiresAt) {
		return new CheckResult(true, statusCode, latencyMs, null, certExpiresAt);
	}

	public static CheckResult failed(Integer statusCode, Integer latencyMs, String errorMessage, Instant certExpiresAt) {
		return new CheckResult(false, statusCode, latencyMs, errorMessage, certExpiresAt);
	}

	public static CheckResult failed(String errorMessage) {
		return failed(null, null, errorMessage, null);
	}
}
