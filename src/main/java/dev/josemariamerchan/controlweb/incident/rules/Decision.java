package dev.josemariamerchan.controlweb.incident.rules;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/** What the engine wants done about one incident type: open a new incident or close the open one. */
public sealed interface Decision {

	IncidentType type();

	/** @param details human-readable explanation stored with the incident */
	record Open(IncidentType type, String details) implements Decision {
	}

	/** @param reason why it is closed ("Site recovered...", "Rule disabled", "Site deactivated"...) */
	record Close(IncidentType type, String reason) implements Decision {
	}
}
