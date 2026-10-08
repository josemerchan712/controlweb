package dev.josemariamerchan.controlweb.config;

import java.util.List;
import java.util.regex.Pattern;

/** Validation shared by every setting that lists browser origins allowed by CORS: exact origins only, no wildcards. */
public final class CorsOrigins {

	private static final Pattern EXACT_ORIGIN = Pattern.compile("^https?://[^/\\s*]+$");

	private CorsOrigins() {
	}

	/**
	 * Trims the entries, drops blanks, and requires each one to be {@code scheme://host[:port]}: no {@code *},
	 * no path, no missing scheme.
	 *
	 * @param environmentVariable name shown in the error ("CORS_ALLOWED_ORIGINS"), never the value of other settings
	 * @throws InvalidSecurityConfigurationException so the application refuses to start
	 */
	public static List<String> exact(List<String> origins, String environmentVariable) {
		List<String> clean = origins == null ? List.of()
				: origins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
		for (String origin : clean) {
			if (!EXACT_ORIGIN.matcher(origin).matches()) {
				throw new InvalidSecurityConfigurationException(
						"%s contains an invalid origin: '%s'. Wildcards, paths and missing schemes are not allowed."
								.formatted(environmentVariable, origin),
						"Set %s to a comma separated list of exact origins, e.g. https://portfolio.example.com"
								.formatted(environmentVariable));
			}
		}
		return clean;
	}
}
