package dev.josemariamerchan.controlweb.monitoring.service;

import java.util.Set;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;
import dev.josemariamerchan.controlweb.monitoring.dto.LastCheckView;

/**
 * The derived state of a site, in order of precedence:
 * <ol>
 * <li>PAUSED: the site is not active (whatever else is recorded about it);</li>
 * <li>PENDING: active but never checked;</li>
 * <li>DOWN: an open DOWN incident, or the last check failed;</li>
 * <li>DEGRADED: it is up but has an open HIGH_LATENCY or SSL_EXPIRING incident;</li>
 * <li>UP.</li>
 * </ol>
 */
public final class SiteStateCalculator {

	private SiteStateCalculator() {
	}

	public static SiteState derive(boolean active, LastCheckView lastCheck, Set<IncidentType> openIncidentTypes) {
		if (!active) {
			return SiteState.PAUSED;
		}
		if (lastCheck == null) {
			return SiteState.PENDING;
		}
		if (openIncidentTypes.contains(IncidentType.DOWN) || !lastCheck.up()) {
			return SiteState.DOWN;
		}
		if (openIncidentTypes.contains(IncidentType.HIGH_LATENCY) || openIncidentTypes.contains(IncidentType.SSL_EXPIRING)) {
			return SiteState.DEGRADED;
		}
		return SiteState.UP;
	}
}
