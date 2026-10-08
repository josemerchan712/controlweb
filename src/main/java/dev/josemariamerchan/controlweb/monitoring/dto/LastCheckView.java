package dev.josemariamerchan.controlweb.monitoring.dto;

import java.time.Instant;

public record LastCheckView(boolean up, Integer statusCode, Integer latencyMs, Instant checkedAt, Instant certExpiresAt) {
}
