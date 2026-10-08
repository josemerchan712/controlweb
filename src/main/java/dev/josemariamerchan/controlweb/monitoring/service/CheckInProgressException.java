package dev.josemariamerchan.controlweb.monitoring.service;

/** A check of this site is already running; a second one is refused instead of queued. */
public class CheckInProgressException extends RuntimeException {

	public CheckInProgressException(Long siteId) {
		super("A check of site %d is already in progress".formatted(siteId));
	}
}
