package dev.josemariamerchan.controlweb.config;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the API from a browser (env var {@code CORS_ALLOWED_ORIGINS}, comma
 * separated). Only exact origins ({@code scheme://host[:port]}) are accepted: no wildcards. Empty
 * means no cross-origin access at all.
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	private static final Pattern EXACT_ORIGIN = Pattern.compile("^https?://[^/\\s*]+$");

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of()
				: allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
		for (String origin : allowedOrigins) {
			if (!EXACT_ORIGIN.matcher(origin).matches()) {
				throw new InvalidSecurityConfigurationException(
						"CORS_ALLOWED_ORIGINS contains an invalid origin: '%s'. Wildcards, paths and missing schemes are not allowed."
								.formatted(origin),
						"Set CORS_ALLOWED_ORIGINS to a comma separated list of exact origins, e.g. http://localhost:5173");
			}
		}
	}
}
