package dev.josemariamerchan.controlweb.monitoring.service;

import java.time.Duration;
import java.util.Locale;

import dev.josemariamerchan.controlweb.common.web.InvalidQueryException;

/** The periods accepted by {@code GET /api/sites/{id}/stats?period=}. */
public enum StatsPeriod {

	LAST_24_HOURS("24h", Duration.ofHours(24)),
	LAST_7_DAYS("7d", Duration.ofDays(7)),
	LAST_30_DAYS("30d", Duration.ofDays(30));

	/** Used when the request has no {@code period}. */
	public static final StatsPeriod DEFAULT = LAST_24_HOURS;

	private final String label;
	private final Duration duration;

	StatsPeriod(String label, Duration duration) {
		this.label = label;
		this.duration = duration;
	}

	public String label() {
		return label;
	}

	public Duration duration() {
		return duration;
	}

	/** @param value "24h", "7d" or "30d" (case-insensitive); null or blank means the default (24h) */
	public static StatsPeriod parse(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT;
		}
		String text = value.trim().toLowerCase(Locale.ROOT);
		for (StatsPeriod period : values()) {
			if (period.label.equals(text)) {
				return period;
			}
		}
		throw new InvalidQueryException("period must be one of 24h, 7d, 30d");
	}
}
