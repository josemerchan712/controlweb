package dev.josemariamerchan.controlweb.monitoring.dto;

import java.time.Instant;

import dev.josemariamerchan.controlweb.monitoring.domain.Check;

/** One stored check in the history of a site (the site is already given by the URL). */
public record CheckHistoryItem(Long id, Instant checkedAt, boolean up, Integer statusCode, Integer latencyMs,
		String errorMessage, Instant certExpiresAt) {

	public static CheckHistoryItem from(Check check) {
		return new CheckHistoryItem(check.getId(), check.getCheckedAt(), check.isUp(), check.getStatusCode(),
				check.getLatencyMs(), check.getErrorMessage(), check.getCertExpiresAt());
	}
}
