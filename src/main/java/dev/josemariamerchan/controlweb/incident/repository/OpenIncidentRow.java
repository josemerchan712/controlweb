package dev.josemariamerchan.controlweb.incident.repository;

import java.time.Instant;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/** An open incident reduced to what the status board needs (no entity, no lazy association). */
public record OpenIncidentRow(Long siteId, IncidentType type, Instant openedAt) {
}
