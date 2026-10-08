package dev.josemariamerchan.controlweb.alert.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.alert.service.AlertTestService;
import dev.josemariamerchan.controlweb.alert.service.AlertTestService.TestAlertResult;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

	private final AlertTestService service;

	public AlertController(AlertTestService service) {
		this.service = service;
	}

	/**
	 * Sends a test message through the configured notifier: 200 if it was delivered, 503 (problem detail
	 * without sensitive data) if Telegram is not configured or fails, 429 if called again within 10 seconds.
	 */
	@PostMapping("/test")
	public TestAlertResult sendTest() {
		return service.sendTestAlert();
	}
}
