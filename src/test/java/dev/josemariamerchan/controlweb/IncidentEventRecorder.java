package dev.josemariamerchan.controlweb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

/**
 * Test double for the real event consumers: records the incident events exactly as a production
 * listener sees them, i.e. only AFTER the publishing transaction has committed.
 */
public class IncidentEventRecorder {

	private final List<Object> events = new CopyOnWriteArrayList<>();

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onOpened(IncidentOpened event) {
		events.add(event);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onResolved(IncidentResolved event) {
		events.add(event);
	}

	public void clear() {
		events.clear();
	}

	public List<Object> all() {
		return new ArrayList<>(events);
	}

	public List<IncidentOpened> opened() {
		return events.stream().filter(IncidentOpened.class::isInstance).map(IncidentOpened.class::cast).toList();
	}

	public List<IncidentResolved> resolved() {
		return events.stream().filter(IncidentResolved.class::isInstance).map(IncidentResolved.class::cast).toList();
	}
}
