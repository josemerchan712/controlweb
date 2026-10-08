package dev.josemariamerchan.controlweb.alert.service;

/** The test alert could not be delivered (not configured, or Telegram failed). The message is safe to show. */
public class AlertDeliveryException extends RuntimeException {

	public AlertDeliveryException(String message) {
		super(message);
	}
}
