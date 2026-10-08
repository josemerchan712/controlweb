package dev.josemariamerchan.controlweb.monitoring.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for the status board and the statistics. Plain SQL through {@link JdbcClient}: the results are
 * small records, so nothing is loaded as an entity (no lazy proxies, no hidden extra queries).
 */
@Repository
public class CheckQueries {

	public record LatestCheck(Long siteId, boolean up, Integer statusCode, Integer latencyMs, Instant checkedAt,
			Instant certExpiresAt) {
	}

	public record Stats(long totalChecks, long successfulChecks, BigDecimal uptimePercent, BigDecimal avgLatencyMs,
			BigDecimal p95LatencyMs, Instant firstCheckAt, Instant lastCheckAt) {
	}

	private static final String LATEST_PER_SITE = """
			select c.site_id, c.up, c.status_code, c.latency_ms, c.checked_at, c.cert_expires_at
			from sites s
			cross join lateral (
			    select * from checks
			    where site_id = s.id
			    order by checked_at desc, id desc
			    limit 1
			) c
			where s.id in (:siteIds)
			""";

	/**
	 * The uptime is successful / ALL checks of the window. Latencies only count successful checks that have a
	 * latency. With no checks there is no uptime and no latency (null), never an invented 100 %.
	 */
	private static final String STATS = """
			select count(*) as total_checks,
			       count(*) filter (where up) as successful_checks,
			       round(100.0 * count(*) filter (where up) / nullif(count(*), 0), 2) as uptime_percent,
			       round(avg(latency_ms) filter (where up and latency_ms is not null), 2) as avg_latency_ms,
			       round((percentile_cont(0.95) within group (order by latency_ms)
			              filter (where up and latency_ms is not null))::numeric, 2) as p95_latency_ms,
			       min(checked_at) as first_check_at,
			       max(checked_at) as last_check_at
			from checks
			where site_id = :siteId
			  and checked_at >= :windowStart
			  and checked_at <= :windowEnd
			""";

	private final JdbcClient jdbc;

	public CheckQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The most recent check of each of the given sites (sites without checks are simply absent). One statement. */
	public List<LatestCheck> latestPerSite(Collection<Long> siteIds) {
		if (siteIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(LATEST_PER_SITE).param("siteIds", siteIds)
				.query((rs, rowNumber) -> new LatestCheck(rs.getLong("site_id"), rs.getBoolean("up"),
						integer(rs, "status_code"), integer(rs, "latency_ms"), instant(rs, "checked_at"),
						instant(rs, "cert_expires_at")))
				.list();
	}

	/** Statistics over {@code windowStart <= checked_at <= windowEnd}. Always returns exactly one row. */
	public Stats stats(Long siteId, Instant windowStart, Instant windowEnd) {
		return jdbc.sql(STATS)
				.param("siteId", siteId)
				.param("windowStart", OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC))
				.param("windowEnd", OffsetDateTime.ofInstant(windowEnd, ZoneOffset.UTC))
				.query((rs, rowNumber) -> new Stats(rs.getLong("total_checks"), rs.getLong("successful_checks"),
						rs.getBigDecimal("uptime_percent"), rs.getBigDecimal("avg_latency_ms"),
						rs.getBigDecimal("p95_latency_ms"), instant(rs, "first_check_at"), instant(rs, "last_check_at")))
				.single();
	}

	private static Integer integer(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, Integer.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
