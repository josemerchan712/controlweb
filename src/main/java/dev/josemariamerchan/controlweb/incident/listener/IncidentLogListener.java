package dev.josemariamerchan.controlweb.incident.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

/**
 * Logs incidents once they are committed. The Telegram notifier (next step) listens to the same events
 * in the same way: AFTER_COMMIT, so a rolled-back incident is never announced.
 */
@Component
public class IncidentLogListener {

	private static final Logger log = LoggerFactory.getLogger(IncidentLogListener.class);

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onOpened(IncidentOpened event) {
		log.info("Incident {} opened: {} on site {} ({})", event.incidentId(), event.type(), event.siteId(),
				event.siteName());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onResolved(IncidentResolved event) {
		log.info("Incident {} resolved: {} on site {} ({}) after {}", event.incidentId(), event.type(),
				event.siteId(), event.siteName(), event.duration());
	}
}
