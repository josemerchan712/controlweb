package dev.josemariamerchan.controlweb.incident.dto;

import java.util.Locale;

import dev.josemariamerchan.controlweb.common.web.InvalidQueryException;

/** The {@code status} of an incident, and the values accepted by {@code GET /api/incidents?status=}. */
public enum IncidentStatus {
	OPEN, RESOLVED;

	/** @return null when the value is absent or blank (no filter) */
	public static IncidentStatus parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidQueryException("status must be open or resolved");
		}
	}
}
