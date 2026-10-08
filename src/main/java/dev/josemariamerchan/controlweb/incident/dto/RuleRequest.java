package dev.josemariamerchan.controlweb.incident.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/sites/{id}/rules/{type}}. {@code enabled} is optional: when omitted the rule
 * keeps its current state. The upper limit of the threshold depends on the rule type and is checked
 * by the service.
 */
public record RuleRequest(@NotNull @Min(1) Integer threshold, Boolean enabled) {
}
