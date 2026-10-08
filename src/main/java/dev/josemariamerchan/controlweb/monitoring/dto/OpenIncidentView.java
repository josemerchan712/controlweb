package dev.josemariamerchan.controlweb.monitoring.dto;

import java.time.Instant;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/** An incident that is still open: its type and since when. */
public record OpenIncidentView(IncidentType type, Instant since) {
}
