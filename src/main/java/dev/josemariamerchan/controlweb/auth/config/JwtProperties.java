package dev.josemariamerchan.controlweb.auth.config;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import dev.josemariamerchan.controlweb.config.InvalidSecurityConfigurationException;

/**
 * JWT settings. The application does not start if the secret is missing or weaker than 256 bits.
 *
 * @param secret     Base64 of at least 32 random bytes (env var {@code JWT_SECRET}); there is no default
 * @param ttlMinutes lifetime of an access token (env var {@code JWT_TTL_MINUTES})
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, @DefaultValue("60") int ttlMinutes) {

	public static final int MIN_SECRET_BYTES = 32;
	private static final String HOW_TO_FIX = "Set JWT_SECRET (environment or .env) to the Base64 of at least "
			+ MIN_SECRET_BYTES + " random bytes. In PowerShell: "
			+ "$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); "
			+ "[Convert]::ToBase64String($b)";

	public JwtProperties {
		if (secret == null || secret.isBlank()) {
			throw new InvalidSecurityConfigurationException("JWT_SECRET is not set.", HOW_TO_FIX);
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(secret.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidSecurityConfigurationException("JWT_SECRET is not valid Base64.", HOW_TO_FIX);
		}
		if (decoded.length < MIN_SECRET_BYTES) {
			throw new InvalidSecurityConfigurationException(
					"JWT_SECRET decodes to %d bytes, but at least %d are required.".formatted(decoded.length,
							MIN_SECRET_BYTES),
					HOW_TO_FIX);
		}
		if (ttlMinutes < 1 || ttlMinutes > 1440) {
			throw new InvalidSecurityConfigurationException("JWT_TTL_MINUTES must be between 1 and 1440.",
					"Set JWT_TTL_MINUTES to a number of minutes between 1 and 1440 (1 day).");
		}
	}

	/** The raw signing key. */
	public byte[] secretBytes() {
		return Base64.getDecoder().decode(secret.trim());
	}

	@Override
	public String toString() {
		return "JwtProperties[secret=<redacted>, ttlMinutes=" + ttlMinutes + "]";
	}
}
