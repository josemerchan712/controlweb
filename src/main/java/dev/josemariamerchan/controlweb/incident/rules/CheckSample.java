package dev.josemariamerchan.controlweb.incident.rules;

import java.time.Instant;

/** The facts of one stored check that the rules care about. */
public record CheckSample(Instant checkedAt, boolean up, Integer statusCode, Integer latencyMs, String errorMessage,
		Instant certExpiresAt) {
}
