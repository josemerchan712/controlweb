package dev.josemariamerchan.controlweb.config;

/**
 * Thrown while binding security-related settings (JWT secret, admin credentials, CORS origins)
 * when they are missing or too weak, so the application refuses to start. Messages must never
 * contain the offending value, only what is wrong and how to fix it.
 */
public class InvalidSecurityConfigurationException extends RuntimeException {

	private final String action;

	public InvalidSecurityConfigurationException(String message, String action) {
		super(message);
		this.action = action;
	}

	/** What the operator should do to fix the configuration. */
	public String getAction() {
		return action;
	}
}
