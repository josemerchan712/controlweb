package dev.josemariamerchan.controlweb.incident.event;

import java.time.Instant;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * Published when a new incident has been stored. Listen with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so nothing is announced for an incident
 * whose transaction rolled back. It carries the site NAME, never its URL (which may hold secrets).
 */
public record IncidentOpened(Long incidentId, Long siteId, String siteName, IncidentType type, Instant openedAt,
		String details) {
}
