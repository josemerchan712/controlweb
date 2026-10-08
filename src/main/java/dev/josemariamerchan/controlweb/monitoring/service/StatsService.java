package dev.josemariamerchan.controlweb.monitoring.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.monitoring.dto.StatsResponse;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckQueries.Stats;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Uptime and latency statistics of a site, computed by the database over the closed window
 * {@code [now - period, now]} where {@code now} comes from the application's Clock (checks dated in the
 * future are not counted). Read-only.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

	private final SiteRepository sites;
	private final CheckQueries queries;
	private final Clock clock;

	public StatsService(SiteRepository sites, CheckQueries queries, Clock clock) {
		this.sites = sites;
		this.queries = queries;
		this.clock = clock;
	}

	/**
	 * @param period "24h", "7d" or "30d"; absent means 24h
	 * @throws dev.josemariamerchan.controlweb.common.web.InvalidQueryException for any other value
	 * @throws SiteNotFoundException if the site does not exist
	 */
	public StatsResponse stats(Long siteId, String period) {
		StatsPeriod statsPeriod = StatsPeriod.parse(period);
		if (!sites.existsById(siteId)) {
			throw new SiteNotFoundException(siteId);
		}
		Instant windowEnd = clock.instant();
		Instant windowStart = windowEnd.minus(statsPeriod.duration());
		Stats row = queries.stats(siteId, windowStart, windowEnd);
		return new StatsResponse(siteId, statsPeriod.label(), windowStart, windowEnd, row.totalChecks(),
				row.successfulChecks(), row.uptimePercent(), row.avgLatencyMs(), row.p95LatencyMs(), row.firstCheckAt(),
				row.lastCheckAt());
	}
}
