package dev.josemariamerchan.controlweb.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the PRIVATE API from a browser (env var {@code CORS_ALLOWED_ORIGINS}, comma
 * separated). Only exact origins ({@code scheme://host[:port]}) are accepted: no wildcards. Empty
 * means no cross-origin access at all. The public status endpoint has its own list ({@link PublicProperties}).
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = CorsOrigins.exact(allowedOrigins, "CORS_ALLOWED_ORIGINS");
	}
}
