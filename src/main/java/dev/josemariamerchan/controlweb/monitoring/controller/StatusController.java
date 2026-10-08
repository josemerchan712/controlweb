package dev.josemariamerchan.controlweb.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.monitoring.dto.StatusResponse;
import dev.josemariamerchan.controlweb.monitoring.service.StatusService;

@RestController
@RequestMapping("/api/status")
public class StatusController {

	private final StatusService service;

	public StatusController(StatusService service) {
		this.service = service;
	}

	/** The status board: every site with its derived state, last check and open incidents, plus a summary. */
	@GetMapping
	public StatusResponse status() {
		return service.currentStatus();
	}
}
