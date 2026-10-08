package dev.josemariamerchan.controlweb.monitoring.dto;

import java.time.Instant;

import dev.josemariamerchan.controlweb.monitoring.domain.Check;

public record CheckResponse(
		Long id,
		Long siteId,
		Instant checkedAt,
		boolean up,
		Integer statusCode,
		Integer latencyMs,
		String errorMessage,
		Instant certExpiresAt) {

	public static CheckResponse from(Check check) {
		return new CheckResponse(check.getId(), check.getSite().getId(), check.getCheckedAt(), check.isUp(),
				check.getStatusCode(), check.getLatencyMs(), check.getErrorMessage(), check.getCertExpiresAt());
	}
}
