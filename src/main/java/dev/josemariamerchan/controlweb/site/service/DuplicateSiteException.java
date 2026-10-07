package dev.josemariamerchan.controlweb.site.service;

public class DuplicateSiteException extends RuntimeException {

	public DuplicateSiteException(String url) {
		super("A site with URL %s already exists".formatted(url));
	}
}
