package dev.josemariamerchan.controlweb.auth.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.josemariamerchan.controlweb.config.InvalidSecurityConfigurationException;

/**
 * The single panel administrator, created at startup from the environment.
 *
 * @param email    env var {@code ADMIN_EMAIL}
 * @param password env var {@code ADMIN_PASSWORD}: at least 12 characters and at most 72 bytes
 *                 (BCrypt ignores anything beyond that, so a longer password would be a false sense of security)
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String email, String password) {

	public static final int MIN_PASSWORD_LENGTH = 12;
	public static final int MAX_PASSWORD_BYTES = 72;
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	public AdminProperties {
		if (email == null || !EMAIL.matcher(email.trim()).matches()) {
			throw new InvalidSecurityConfigurationException("ADMIN_EMAIL is missing or is not a valid email address.",
					"Set ADMIN_EMAIL (environment or .env) to the administrator's email.");
		}
		if (password == null || password.isBlank()) {
			throw new InvalidSecurityConfigurationException("ADMIN_PASSWORD is not set.",
					"Set ADMIN_PASSWORD (environment or .env) to a password of at least %d characters."
							.formatted(MIN_PASSWORD_LENGTH));
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new InvalidSecurityConfigurationException(
					"ADMIN_PASSWORD is too short: it must have at least %d characters.".formatted(MIN_PASSWORD_LENGTH),
					"Set ADMIN_PASSWORD to a longer password (at least %d characters).".formatted(MIN_PASSWORD_LENGTH));
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new InvalidSecurityConfigurationException(
					"ADMIN_PASSWORD is too long: BCrypt only supports up to %d bytes.".formatted(MAX_PASSWORD_BYTES),
					"Set ADMIN_PASSWORD to a password of at most %d bytes.".formatted(MAX_PASSWORD_BYTES));
		}
	}

	public String normalizedEmail() {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "AdminProperties[email=" + email + ", password=<redacted>]";
	}
}
