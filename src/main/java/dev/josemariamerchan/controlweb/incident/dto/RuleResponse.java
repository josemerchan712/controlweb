package dev.josemariamerchan.controlweb.incident.dto;

import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/** @param unit what the threshold counts: "failed checks in a row", "ms" or "days" */
public record RuleResponse(IncidentType type, int threshold, String unit, boolean enabled) {

	public static RuleResponse from(AlertRule rule) {
		return new RuleResponse(rule.getType(), rule.getThreshold(), rule.getType().unit(), rule.isEnabled());
	}
}
