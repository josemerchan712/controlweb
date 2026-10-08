package dev.josemariamerchan.controlweb.alert;

import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;

/**
 * What happened to an alert. {@code detail} is a short, safe explanation (never a raw exception
 * message, never a URL, never a secret); it may be shown to the API user.
 */
public record SendResult(Status status, String detail) {

	public enum Status {
		/** Delivered to the external channel. */
		SENT,
		/** There is no external channel (only the log); nothing was delivered. */
		NOT_CONFIGURED,
		/** An external channel exists but the alert could not be delivered. */
		FAILED
	}

	public SendResult {
		detail = MessageSanitizer.sanitize(detail);
	}

	public static SendResult sent() {
		return new SendResult(Status.SENT, null);
	}

	public static SendResult notConfigured() {
		return new SendResult(Status.NOT_CONFIGURED, "No alert channel is configured");
	}

	public static SendResult failed(String detail) {
		return new SendResult(Status.FAILED, detail);
	}

	public boolean isSent() {
		return status == Status.SENT;
	}
}
