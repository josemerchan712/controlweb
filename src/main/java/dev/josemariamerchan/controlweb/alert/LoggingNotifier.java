package dev.josemariamerchan.controlweb.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when Telegram is not configured: the alert is written to the log, nothing leaves the machine. */
public class LoggingNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

	@Override
	public SendResult send(String htmlMessage) {
		log.info("ALERT (not delivered, Telegram is not configured): {}", htmlMessage);
		return SendResult.notConfigured();
	}
}
