package dev.josemariamerchan.controlweb.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import dev.josemariamerchan.controlweb.monitoring.dto.StatusResponse;
import dev.josemariamerchan.controlweb.monitoring.service.StatusService;

@Tag(name = "Status", description = "Current state of every site")
@RestController
@RequestMapping("/api/status")
public class StatusController {

	private final StatusService service;

	public StatusController(StatusService service) {
		this.service = service;
	}

	/** The status board: every site with its derived state, last check and open incidents, plus a summary. */
	@Operation(summary = "Current status",
			description = "State of every site (up, down, degraded, paused, unknown) with its last check and open incidents.")
	@GetMapping
	public StatusResponse status() {
		return service.currentStatus();
	}
}
