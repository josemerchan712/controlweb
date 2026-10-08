package dev.josemariamerchan.controlweb.monitoring.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Statistics of one site over a period.
 *
 * @param period           "24h", "7d" or "30d"
 * @param windowStart      start of the period asked for ({@code now - period}), inclusive
 * @param windowEnd        end of the period ({@code now}), inclusive
 * @param totalChecks      checks inside the window
 * @param successfulChecks those that were up
 * @param uptimePercent    successful / total * 100, two decimals; null when there are no checks (never 100 %)
 * @param avgLatencyMs     mean latency of the successful checks that have a latency, two decimals; null if none
 * @param p95LatencyMs     95th percentile (continuous) of that same latency, two decimals; null if none
 * @param firstCheckAt     the real range covered: time of the first check inside the window (null if none)
 * @param lastCheckAt      and of the last one
 */
public record StatsResponse(Long siteId, String period, Instant windowStart, Instant windowEnd, long totalChecks,
		long successfulChecks, BigDecimal uptimePercent, BigDecimal avgLatencyMs, BigDecimal p95LatencyMs,
		Instant firstCheckAt, Instant lastCheckAt) {
}
