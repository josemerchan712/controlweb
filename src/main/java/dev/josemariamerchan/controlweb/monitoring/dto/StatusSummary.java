package dev.josemariamerchan.controlweb.monitoring.dto;

import java.util.List;

import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;

/** How many sites are in each state. */
public record StatusSummary(int total, int up, int degraded, int down, int pending, int paused) {

	public static StatusSummary of(List<SiteStatusView> sites) {
		return new StatusSummary(sites.size(), count(sites, SiteState.UP), count(sites, SiteState.DEGRADED),
				count(sites, SiteState.DOWN), count(sites, SiteState.PENDING), count(sites, SiteState.PAUSED));
	}

	private static int count(List<SiteStatusView> sites, SiteState state) {
		return (int) sites.stream().filter(site -> site.status() == state).count();
	}
}
