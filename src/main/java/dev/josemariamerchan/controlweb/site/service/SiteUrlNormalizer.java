package dev.josemariamerchan.controlweb.site.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Turns the URL typed by the user into one canonical form so that the UNIQUE constraint on
 * {@code sites.url} really prevents duplicates:
 * lower-case scheme and host, no trailing dot in the host, no default port, no fragment and no
 * redundant root slash. Paths and queries are case-sensitive and kept as they are.
 * URLs with embedded credentials ({@code user:pass@host}) are rejected.
 */
public final class SiteUrlNormalizer {

	private SiteUrlNormalizer() {
	}

	public static URI normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidSiteUrlException("URL must not be blank");
		}
		URI uri;
		try {
			uri = new URI(raw.trim());
		}
		catch (URISyntaxException ex) {
			throw new InvalidSiteUrlException("URL is not valid");
		}

		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new InvalidSiteUrlException("URL must start with http:// or https://");
		}
		// Checked on the raw authority: java.net.URI reports an empty user-info (https://@host) as absent.
		String authority = uri.getRawAuthority();
		if (authority != null && authority.contains("@")) {
			throw new InvalidSiteUrlException("URL must not contain credentials");
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			throw new InvalidSiteUrlException("URL must contain a valid host");
		}
		host = host.toLowerCase(Locale.ROOT);
		if (host.endsWith(".") && host.length() > 1) {
			host = host.substring(0, host.length() - 1);
		}

		int port = uri.getPort();
		boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

		String path = uri.getRawPath();
		if (path == null || path.equals("/")) {
			path = "";
		}

		StringBuilder normalized = new StringBuilder(scheme).append("://").append(host);
		if (port != -1 && !defaultPort) {
			normalized.append(':').append(port);
		}
		normalized.append(path);
		if (uri.getRawQuery() != null) {
			normalized.append('?').append(uri.getRawQuery());
		}
		return URI.create(normalized.toString());
	}
}
