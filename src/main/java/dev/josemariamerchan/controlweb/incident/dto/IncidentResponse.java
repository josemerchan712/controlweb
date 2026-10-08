package dev.josemariamerchan.controlweb.incident.dto;

import java.time.Duration;
import java.time.Instant;

import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * An incident as the API shows it.
 *
 * @param ongoing         true while it is open ("en curso")
 * @param durationSeconds how long it lasted, only once resolved; null while ongoing
 */
public record IncidentResponse(Long id, Long siteId, String siteName, IncidentType type, IncidentStatus status,
		Instant openedAt, Instant resolvedAt, boolean ongoing, Long durationSeconds, String details) {

	/** Needs the incident's site to be loaded (the repository query fetches it together with the incident). */
	public static IncidentResponse from(Incident incident) {
		boolean ongoing = incident.isOpen();
		Long duration = ongoing ? null : Duration.between(incident.getOpenedAt(), incident.getResolvedAt()).toSeconds();
		return new IncidentResponse(incident.getId(), incident.getSite().getId(), incident.getSite().getName(),
				incident.getType(), ongoing ? IncidentStatus.OPEN : IncidentStatus.RESOLVED, incident.getOpenedAt(),
				incident.getResolvedAt(), ongoing, duration, incident.getDetails());
	}
}
