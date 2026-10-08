package dev.josemariamerchan.controlweb.incident.domain;

/** The kinds of problems the rule engine can detect, with the unit and allowed range of their threshold. */
public enum IncidentType {

	/** The site failed N consecutive checks. */
	DOWN("failed checks in a row", 1, 100),

	/** Response latency is above a threshold in milliseconds. */
	HIGH_LATENCY("ms", 1, 600_000),

	/** The TLS certificate expires within a number of days. */
	SSL_EXPIRING("days", 1, 365);

	private final String unit;
	private final int minThreshold;
	private final int maxThreshold;

	IncidentType(String unit, int minThreshold, int maxThreshold) {
		this.unit = unit;
		this.minThreshold = minThreshold;
		this.maxThreshold = maxThreshold;
	}

	public String unit() {
		return unit;
	}

	public int minThreshold() {
		return minThreshold;
	}

	public int maxThreshold() {
		return maxThreshold;
	}
}
