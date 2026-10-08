package dev.josemariamerchan.controlweb.monitoring.dto;

import java.util.List;

import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;

/** One site on the status board. {@code lastCheck} is null while the site is PENDING. */
public record SiteStatusView(Long id, String name, String url, boolean active, SiteState status, LastCheckView lastCheck,
		List<OpenIncidentView> openIncidents) {
}
