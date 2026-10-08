package dev.josemariamerchan.controlweb.incident.event;

import java.time.Duration;
import java.time.Instant;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * Published when an open incident has been closed. Listen with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * @param duration how long the incident lasted ({@code resolvedAt - openedAt}, never negative)
 * @param details  the incident's details including how it was closed
 */
public record IncidentResolved(Long incidentId, Long siteId, String siteName, IncidentType type, Instant openedAt,
		Instant resolvedAt, Duration duration, String details) {
}
