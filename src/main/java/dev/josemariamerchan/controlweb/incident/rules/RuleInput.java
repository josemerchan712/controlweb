package dev.josemariamerchan.controlweb.incident.rules;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * Everything the engine needs, read from the database by the caller. The engine keeps no memory
 * between calls, so the same input always gives the same decisions.
 *
 * @param now            the evaluation time (from the application's Clock)
 * @param siteActive     whether the site is currently being monitored
 * @param rules          the site's alert rules
 * @param checks         the site's most recent checks, NEWEST FIRST
 * @param openIncidents  the types that currently have an open incident for the site
 */
public record RuleInput(Instant now, boolean siteActive, List<RuleConfig> rules, List<CheckSample> checks,
		Set<IncidentType> openIncidents) {

	public RuleInput {
		rules = List.copyOf(rules);
		checks = List.copyOf(checks);
		openIncidents = Set.copyOf(openIncidents);
	}

	Optional<RuleConfig> ruleFor(IncidentType type) {
		return rules.stream().filter(rule -> rule.type() == type).findFirst();
	}

	boolean isOpen(IncidentType type) {
		return openIncidents.contains(type);
	}
}
