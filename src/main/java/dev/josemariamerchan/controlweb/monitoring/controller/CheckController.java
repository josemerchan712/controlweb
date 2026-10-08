package dev.josemariamerchan.controlweb.monitoring.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;

@RestController
@RequestMapping("/api/sites")
public class CheckController {

	private final CheckRunner runner;

	public CheckController(CheckRunner runner) {
		this.runner = runner;
	}

	/** Checks the site now (even if it is inactive) and returns the stored result; 409 if a check is running. */
	@PostMapping("/{id}/check")
	public CheckResponse checkNow(@PathVariable Long id) {
		return runner.runManually(id);
	}
}
