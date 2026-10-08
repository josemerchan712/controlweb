package dev.josemariamerchan.controlweb.incident.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.dto.RuleRequest;
import dev.josemariamerchan.controlweb.incident.dto.RuleResponse;
import dev.josemariamerchan.controlweb.incident.service.RuleService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sites/{siteId}/rules")
public class RuleController {

	private final RuleService service;

	public RuleController(RuleService service) {
		this.service = service;
	}

	@GetMapping
	public List<RuleResponse> list(@PathVariable Long siteId) {
		return service.list(siteId);
	}

	/** {@code type} is DOWN, HIGH_LATENCY or SSL_EXPIRING. Applies from the next check. */
	@PutMapping("/{type}")
	public RuleResponse update(@PathVariable Long siteId, @PathVariable IncidentType type,
			@Valid @RequestBody RuleRequest request) {
		return service.update(siteId, type, request);
	}
}
