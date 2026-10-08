package dev.josemariamerchan.controlweb.config;

/**
 * Thrown while binding security-related settings (JWT secret, admin credentials, CORS origins)
 * when they are missing or too weak, so the application refuses to start. Messages must never
 * contain the offending value, only what is wrong and how to fix it.
 */
public class InvalidSecurityConfigurationException extends InvalidConfigurationException {

	public InvalidSecurityConfigurationException(String message, String action) {
		super(message, action);
	}
}
