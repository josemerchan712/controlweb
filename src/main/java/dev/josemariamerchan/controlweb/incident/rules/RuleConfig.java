package dev.josemariamerchan.controlweb.incident.rules;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * One alert rule as the engine sees it.
 *
 * @param threshold consecutive failures (DOWN), milliseconds (HIGH_LATENCY) or days left (SSL_EXPIRING)
 */
public record RuleConfig(IncidentType type, int threshold, boolean enabled) {
}
