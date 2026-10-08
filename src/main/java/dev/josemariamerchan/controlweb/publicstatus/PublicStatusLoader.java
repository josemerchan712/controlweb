package dev.josemariamerchan.controlweb.publicstatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.repository.OpenIncidentRow;
import dev.josemariamerchan.controlweb.monitoring.dto.LastCheckView;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries.LatestCheck;
import dev.josemariamerchan.controlweb.monitoring.service.SiteStateCalculator;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Reads what the public page needs from the database and builds the response.
 *
 * <p><b>Nothing private is ever read.</b> The first query asks only for the sites that are public AND active, and
 * every following query is restricted to those ids; the overall state is computed from those same sites. A private
 * site therefore cannot influence the answer in any way, because it never reaches this code. Always four statements,
 * whatever the number of sites.
 */
@Component
public class PublicStatusLoader {

	private static final Duration UPTIME_WINDOW = Duration.ofDays(30);
	private static final Sort STABLE_ORDER = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

	private final SiteRepository sites;
	private final CheckQueries checkQueries;
	private final IncidentRepository incidents;
	private final Clock clock;

	public PublicStatusLoader(SiteRepository sites, CheckQueries checkQueries, IncidentRepository incidents, Clock clock) {
		this.sites = sites;
		this.checkQueries = checkQueries;
		this.incidents = incidents;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PublicStatusResponse load() {
		Instant now = clock.instant();
		List<Site> publicSites = sites.findByPublicVisibleTrueAndActiveTrue(STABLE_ORDER);
		if (publicSites.isEmpty()) {
			return new PublicStatusResponse(PublicState.UNKNOWN, now, List.of());
		}
		List<Long> ids = publicSites.stream().map(Site::getId).toList();

		Map<Long, LastCheckView> lastChecks = new HashMap<>();
		for (LatestCheck check : checkQueries.latestPerSite(ids)) {
			lastChecks.put(check.siteId(), new LastCheckView(check.up(), check.statusCode(), check.latencyMs(),
					check.checkedAt(), check.certExpiresAt()));
		}
		Map<Long, Set<IncidentType>> openTypes = new HashMap<>();
		for (OpenIncidentRow row : incidents.findOpenRowsForSites(ids)) {
			openTypes.computeIfAbsent(row.siteId(), id -> EnumSet.noneOf(IncidentType.class)).add(row.type());
		}
		Map<Long, BigDecimal> uptimes = checkQueries.uptimePerSite(ids, now.minus(UPTIME_WINDOW), now);

		List<PublicSiteView> views = new ArrayList<>();
		for (Site site : publicSites) {
			Set<IncidentType> open = openTypes.getOrDefault(site.getId(), EnumSet.noneOf(IncidentType.class));
			PublicState state = PublicState.from(SiteStateCalculator.derive(site.isActive(), lastChecks.get(site.getId()), open));
			views.add(new PublicSiteView(site.getName(), state, uptimes.get(site.getId())));
		}
		PublicState overall = PublicState.overall(views.stream().map(PublicSiteView::status).toList());
		return new PublicStatusResponse(overall, now, views);
	}
}
