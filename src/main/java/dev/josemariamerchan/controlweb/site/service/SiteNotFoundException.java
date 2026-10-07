package dev.josemariamerchan.controlweb.site.service;

public class SiteNotFoundException extends RuntimeException {

	public SiteNotFoundException(Long id) {
		super("Site %d not found".formatted(id));
	}
}
