package dev.josemariamerchan.controlweb.incident.domain;

/** The kinds of problems the rule engine can detect. */
public enum IncidentType {
	/** The site failed N consecutive checks. */
	DOWN,
	/** Response latency is above a threshold in milliseconds. */
	HIGH_LATENCY,
	/** The TLS certificate expires within a number of days. */
	SSL_EXPIRING
}
