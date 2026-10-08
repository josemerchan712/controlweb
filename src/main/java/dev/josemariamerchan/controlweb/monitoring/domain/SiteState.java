package dev.josemariamerchan.controlweb.monitoring.domain;

/** The state of a site as shown on the status board, derived from its settings, last check and open incidents. */
public enum SiteState {
	/** Everything fine. */
	UP,
	/** Up, but with an open HIGH_LATENCY or SSL_EXPIRING incident. */
	DEGRADED,
	/** An open DOWN incident, or the last check failed. */
	DOWN,
	/** Active but not checked yet. */
	PENDING,
	/** Deactivated: not being monitored. */
	PAUSED
}
