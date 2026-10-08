package dev.josemariamerchan.controlweb.alert;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

/**
 * Turns incident events into alerts. It runs AFTER the incident's transaction has committed, so nothing is
 * announced for a change that was rolled back, and it only formats the text and queues it: the network
 * call happens on the {@link AlertDispatcher}'s own thread. Whatever fails here is logged and swallowed,
 * so an alert problem can never touch the check, the incident or the scheduler.
 */
@Component
public class IncidentAlertListener {

	private static final Logger log = LoggerFactory.getLogger(IncidentAlertListener.class);

	private final AlertMessageFormatter formatter;
	private final AlertDispatcher dispatcher;

	public IncidentAlertListener(AlertMessageFormatter formatter, AlertDispatcher dispatcher) {
		this.formatter = formatter;
		this.dispatcher = dispatcher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onOpened(IncidentOpened event) {
		queue(() -> formatter.opened(event));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onResolved(IncidentResolved event) {
		queue(() -> formatter.resolved(event));
	}

	private void queue(Supplier<String> message) {
		try {
			dispatcher.dispatch(message.get());
		}
		catch (RuntimeException ex) {
			log.warn("Could not queue an incident alert: {}", ex.getClass().getName());
		}
	}
}
