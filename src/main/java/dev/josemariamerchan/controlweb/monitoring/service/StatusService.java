package dev.josemariamerchan.controlweb.monitoring.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.repository.OpenIncidentRow;
import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;
import dev.josemariamerchan.controlweb.monitoring.dto.LastCheckView;
import dev.josemariamerchan.controlweb.monitoring.dto.OpenIncidentView;
import dev.josemariamerchan.controlweb.monitoring.dto.SiteStatusView;
import dev.josemariamerchan.controlweb.monitoring.dto.StatusResponse;
import dev.josemariamerchan.controlweb.monitoring.dto.StatusSummary;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries.LatestCheck;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * The status board: every site with its derived state, last check and open incidents. It always costs THREE
 * statements, whatever the number of sites (sites, latest check per site, open incidents), and then joins them
 * in memory. Read-only: it changes nothing.
 */
@Service
@Transactional(readOnly = true)
public class StatusService {

	/** Stable order: by name (ignoring case), then id. */
	private static final Sort STABLE_ORDER = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

	private final SiteRepository sites;
	private final CheckQueries checkQueries;
	private final IncidentRepository incidents;
	private final Clock clock;

	public StatusService(SiteRepository sites, CheckQueries checkQueries, IncidentRepository incidents, Clock clock) {
		this.sites = sites;
		this.checkQueries = checkQueries;
		this.incidents = incidents;
		this.clock = clock;
	}

	public StatusResponse currentStatus() {
		List<Site> allSites = sites.findAll(STABLE_ORDER);
		List<Long> ids = allSites.stream().map(Site::getId).toList();

		Map<Long, LastCheckView> lastChecks = new HashMap<>();
		for (LatestCheck check : checkQueries.latestPerSite(ids)) {
			lastChecks.put(check.siteId(), new LastCheckView(check.up(), check.statusCode(), check.latencyMs(),
					check.checkedAt(), check.certExpiresAt()));
		}
		Map<Long, List<OpenIncidentView>> openBySite = new HashMap<>();
		for (OpenIncidentRow row : incidents.findAllOpenRows()) {
			openBySite.computeIfAbsent(row.siteId(), id -> new ArrayList<>()).add(new OpenIncidentView(row.type(), row.openedAt()));
		}

		List<SiteStatusView> views = new ArrayList<>();
		for (Site site : allSites) {
			LastCheckView lastCheck = lastChecks.get(site.getId());
			List<OpenIncidentView> open = openBySite.getOrDefault(site.getId(), List.of());
			Set<IncidentType> openTypes = EnumSet.noneOf(IncidentType.class);
			open.forEach(incident -> openTypes.add(incident.type()));
			SiteState state = SiteStateCalculator.derive(site.isActive(), lastCheck, openTypes);
			views.add(new SiteStatusView(site.getId(), site.getName(), site.getUrl(), site.isActive(), state, lastCheck, open));
		}
		return new StatusResponse(clock.instant(), StatusSummary.of(views), views);
	}
}
