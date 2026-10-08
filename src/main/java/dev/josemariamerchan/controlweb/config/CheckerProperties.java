package dev.josemariamerchan.controlweb.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the HTTP checker.
 *
 * @param allowPrivateHosts when {@code false} (the default, and the only safe value in production)
 *                          sites whose host resolves to a loopback, private, link-local or
 *                          otherwise non-public address are rejected. Only {@code dev} and
 *                          {@code test} profiles turn it on.
 * @param connectTimeout    maximum time to establish the TCP connection (and TLS handshake)
 * @param requestTimeout    maximum time to receive the response status and headers of ONE request
 *                          (each redirect hop is a request of its own)
 * @param maxRedirects      redirects followed (by hand) before the check is declared failed
 * @param userAgent         sent on every request so site owners can recognise the monitor
 */
@ConfigurationProperties("app.checker")
public record CheckerProperties(
		boolean allowPrivateHosts,
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("5") int maxRedirects,
		@DefaultValue("ControlWeb/1.0") String userAgent) {

	@ConstructorBinding
	public CheckerProperties {
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("app.checker.connect-timeout must be positive");
		}
		if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
			throw new IllegalArgumentException("app.checker.request-timeout must be positive");
		}
		if (maxRedirects < 0 || maxRedirects > 10) {
			throw new IllegalArgumentException("app.checker.max-redirects must be between 0 and 10");
		}
		if (userAgent == null || userAgent.isBlank() || userAgent.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("app.checker.user-agent must be a non-blank single line");
		}
	}

	/** Default timeouts, redirect limit and user agent; only the SSRF switch is chosen. */
	public CheckerProperties(boolean allowPrivateHosts) {
		this(allowPrivateHosts, Duration.ofSeconds(5), Duration.ofSeconds(10), 5, "ControlWeb/1.0");
	}
}
