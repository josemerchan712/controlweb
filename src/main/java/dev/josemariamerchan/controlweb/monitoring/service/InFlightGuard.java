package dev.josemariamerchan.controlweb.monitoring.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Remembers which sites are being checked right now, so the periodic cycle and manual checks can
 * never run two checks of the same site at once. Thread-safe and lock-free.
 */
@Component
public class InFlightGuard {

	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	/** @return true if the caller now owns the site and MUST call {@link #release}; false if it is busy */
	public boolean tryAcquire(Long siteId) {
		return inFlight.add(siteId);
	}

	public void release(Long siteId) {
		inFlight.remove(siteId);
	}

	public boolean isInFlight(Long siteId) {
		return inFlight.contains(siteId);
	}

	public int size() {
		return inFlight.size();
	}
}
