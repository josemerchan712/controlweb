package dev.josemariamerchan.controlweb.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The optional "heartbeat": after every scheduler cycle that really ran, ControlWeb pings an external service (for
 * example healthchecks.io). If the pings stop, the external service raises the alarm: the monitor cannot report its
 * own death, so somebody else has to notice the silence.
 *
 * <p>The URL is a SECRET (anyone who knows it can fake "I am alive"): it comes only from the environment, it is never
 * logged, never part of an error message and hidden from {@link #toString()}. Empty (the default) means no heartbeat.
 *
 * @param url         env var {@code HEARTBEAT_URL}; {@code https}, or {@code http} only to the local machine (tests)
 * @param minInterval at most one ping per this time, however often cycles end
 * @param timeout     give up on a ping after this long
 */
@ConfigurationProperties("app.heartbeat")
public record HeartbeatProperties(
		String url,
		@DefaultValue("60s") Duration minInterval,
		@DefaultValue("5s") Duration timeout) {

	private static final int MAX_URL_LENGTH = 2048;
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	public HeartbeatProperties {
		url = url == null ? "" : url.strip();
		if (!url.isEmpty()) {
			validate(url);
		}
		if (minInterval == null || minInterval.compareTo(Duration.ofSeconds(10)) < 0
				|| minInterval.compareTo(Duration.ofHours(1)) > 0) {
			throw invalid("app.heartbeat.min-interval is not valid.", "Set app.heartbeat.min-interval to a duration between 10s and 1h.");
		}
		if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
			throw invalid("app.heartbeat.timeout is not valid.", "Set app.heartbeat.timeout to a duration between 100ms and 30s.");
		}
	}

	public boolean enabled() {
		return !url.isEmpty();
	}

	/** Only the host, which is not secret (the path usually is); for the startup log. */
	public String host() {
		return URI.create(url).getHost();
	}

	private static void validate(String url) {
		URI uri;
		try {
			if (url.length() > MAX_URL_LENGTH) {
				throw new URISyntaxException("", "too long");
			}
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw invalidUrl(); // the URL itself is never echoed: it is a secret
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean secure = scheme.equals("https");
		boolean localPlainHttp = scheme.equals("http") && LOCAL_HOSTS.contains(host);
		if ((!secure && !localPlainHttp) || host.isEmpty() || uri.getUserInfo() != null) {
			throw invalidUrl();
		}
	}

	private static InvalidSecurityConfigurationException invalidUrl() {
		return invalid("HEARTBEAT_URL is not valid: it must be an https URL with a host and no embedded credentials.",
				"Set HEARTBEAT_URL to the ping URL of your external monitor (https://...), or leave it empty to disable the heartbeat.");
	}

	private static InvalidSecurityConfigurationException invalid(String message, String action) {
		return new InvalidSecurityConfigurationException(message, action);
	}

	@Override
	public String toString() {
		return "HeartbeatProperties[enabled=" + enabled() + ", minInterval=" + minInterval + ", timeout=" + timeout + "]";
	}
}
