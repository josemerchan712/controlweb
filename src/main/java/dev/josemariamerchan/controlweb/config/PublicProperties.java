package dev.josemariamerchan.controlweb.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the public status endpoint ({@code GET /api/public/status}), the only API that needs no token.
 *
 * @param corsAllowedOrigins origins (typically the portfolio's) allowed to read it from a browser; env var
 *                           {@code PUBLIC_CORS_ALLOWED_ORIGINS}. Exact origins only, no wildcard; empty = none
 * @param cacheTtl           how long the computed answer is reused before the database is read again
 * @param browserMaxAge      the {@code max-age} sent in {@code Cache-Control} to browsers and CDNs
 */
@ConfigurationProperties("app.public")
public record PublicProperties(
		List<String> corsAllowedOrigins,
		@DefaultValue("15s") Duration cacheTtl,
		@DefaultValue("30s") Duration browserMaxAge) {

	public PublicProperties {
		corsAllowedOrigins = CorsOrigins.exact(corsAllowedOrigins, "PUBLIC_CORS_ALLOWED_ORIGINS");
		if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofMinutes(10)) > 0) {
			throw new IllegalArgumentException("app.public.cache-ttl must be between 0 and 10 minutes");
		}
		if (browserMaxAge == null || browserMaxAge.isNegative() || browserMaxAge.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("app.public.browser-max-age must be between 0 and 1 hour");
		}
	}
}
