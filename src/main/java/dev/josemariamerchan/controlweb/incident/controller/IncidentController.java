package dev.josemariamerchan.controlweb.incident.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.common.web.PageResponse;
import dev.josemariamerchan.controlweb.incident.dto.IncidentResponse;
import dev.josemariamerchan.controlweb.incident.service.IncidentQueryService;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

	private final IncidentQueryService service;

	public IncidentController(IncidentQueryService service) {
		this.service = service;
	}

	/** Newest first. {@code status}: open or resolved (default both). {@code siteId}: only that site's incidents. */
	@GetMapping
	public PageResponse<IncidentResponse> list(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long siteId,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return service.list(status, siteId, page, size);
	}

	@GetMapping("/{id}")
	public IncidentResponse get(@PathVariable Long id) {
		return service.get(id);
	}
}
