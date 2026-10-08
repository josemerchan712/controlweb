package dev.josemariamerchan.controlweb.config;

/**
 * A setting is missing, unsafe or out of range, so the application refuses to start. It carries what is wrong
 * and what to do about it; {@link InvalidConfigurationFailureAnalyzer} prints both as Spring Boot's
 * "APPLICATION FAILED TO START" block. Messages must never contain the offending value when it may be secret.
 */
public class InvalidConfigurationException extends RuntimeException {

	private final String action;

	public InvalidConfigurationException(String message, String action) {
		super(message);
		this.action = action;
	}

	/** What the operator should do to fix the configuration. */
	public String getAction() {
		return action;
	}
}
