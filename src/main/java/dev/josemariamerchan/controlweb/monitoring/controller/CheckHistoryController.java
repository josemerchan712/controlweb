package dev.josemariamerchan.controlweb.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import dev.josemariamerchan.controlweb.common.web.PageResponse;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckHistoryItem;
import dev.josemariamerchan.controlweb.monitoring.dto.StatsResponse;
import dev.josemariamerchan.controlweb.monitoring.service.CheckHistoryService;
import dev.josemariamerchan.controlweb.monitoring.service.StatsService;

/** Read-only views of one site's monitoring data: its check history and its statistics. */
@Tag(name = "History", description = "Check history and statistics")
@RestController
@RequestMapping("/api/sites/{siteId}")
public class CheckHistoryController {

	private final CheckHistoryService history;
	private final StatsService stats;

	public CheckHistoryController(CheckHistoryService history, StatsService stats) {
		this.history = history;
		this.stats = stats;
	}

	/**
	 * Newest first (fixed order). {@code from} / {@code to}: optional ISO-8601 instants with time zone,
	 * inclusive. {@code size} defaults to 50 and may not exceed the configured maximum (100).
	 */
	@Operation(summary = "Check history",
			description = "Paginated checks of a site, newest first, optionally limited to a from/to range (at most 90 days).")
	@GetMapping("/checks")
	public PageResponse<CheckHistoryItem> checks(@PathVariable Long siteId,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return history.history(siteId, from, to, page, size);
	}

	/** {@code period}: 24h (default), 7d or 30d. */
	@Operation(summary = "Site statistics",
			description = "Uptime percentage, average latency and p95 latency over 24h, 7d or 30d, computed in SQL.")
	@GetMapping("/stats")
	public StatsResponse stats(@PathVariable Long siteId, @RequestParam(required = false) String period) {
		return stats.stats(siteId, period);
	}
}
