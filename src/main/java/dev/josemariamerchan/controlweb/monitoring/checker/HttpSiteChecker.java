package dev.josemariamerchan.controlweb.monitoring.checker;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertificateExpiredException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.common.net.HostSafetyValidator;
import dev.josemariamerchan.controlweb.common.net.UnsafeHostException;
import dev.josemariamerchan.controlweb.config.CheckerProperties;
import dev.josemariamerchan.controlweb.site.service.InvalidSiteUrlException;
import dev.josemariamerchan.controlweb.site.service.SiteUrlNormalizer;

/**
 * Checks one URL with a GET request and reports whether it is up, how long it took and when its
 * certificate expires.
 *
 * <ul>
 * <li>{@link #check(String)} <b>never throws</b>: every failure (DNS, timeout, refused connection,
 * TLS problem, interruption, a bug...) becomes a failed {@link CheckResult}.</li>
 * <li>Redirects are followed by hand (at most {@code max-redirects}). Every hop, including the first
 * request, re-validates the host with {@link HostSafetyValidator}, and each hop is a brand new request
 * that carries only {@code User-Agent} and {@code Accept}: no cookies, credentials or referrer
 * ever travel from one hop to the next.</li>
 * <li>Nothing that may contain a secret is stored or logged: URLs are reduced to
 * {@code scheme://host/path} (no query string, no user info).</li>
 * </ul>
 */
@Component
public class HttpSiteChecker {

	private static final Logger log = LoggerFactory.getLogger(HttpSiteChecker.class);

	private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

	private final HttpClient client;
	private final HostSafetyValidator hostSafety;
	private final CertificateInspector certificates;
	private final CheckerProperties properties;

	public HttpSiteChecker(HttpClient client, HostSafetyValidator hostSafety, CertificateInspector certificates,
			CheckerProperties properties) {
		this.client = client;
		this.hostSafety = hostSafety;
		this.certificates = certificates;
		this.properties = properties;
	}

	public CheckResult check(String url) {
		long startNanos = System.nanoTime();
		URI start;
		try {
			start = SiteUrlNormalizer.normalize(url);
		}
		catch (InvalidSiteUrlException ex) {
			return CheckResult.failed("Invalid URL");
		}
		String loggable = safeUrl(start);
		try {
			CheckResult result = execute(start, startNanos);
			log.debug("Checked {}: up={}, status={}, latencyMs={}", loggable, result.up(), result.statusCode(),
					result.latencyMs());
			return result;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return CheckResult.failed("Check interrupted");
		}
		catch (RuntimeException ex) {
			// Last resort. Only the exception type is logged: its message could contain a full URL.
			log.warn("Unexpected failure checking {}: {}", loggable, ex.getClass().getName());
			return CheckResult.failed("Unexpected error: " + ex.getClass().getSimpleName());
		}
	}

	private CheckResult execute(URI first, long startNanos) throws InterruptedException {
		URI current = first;
		Set<String> visited = new HashSet<>();
		visited.add(current.toString());
		int redirects = 0;

		while (true) {
			try {
				hostSafety.assertSafe(current.getHost());
			}
			catch (UnsafeHostException ex) {
				return CheckResult.failed("Blocked: private address");
			}

			HttpRequest request = HttpRequest.newBuilder(current)
					.GET()
					.timeout(properties.requestTimeout())
					.header("User-Agent", properties.userAgent())
					.header("Accept", "*/*")
					.build();

			// The body is never read. ofInputStream() hands over the response as soon as the headers
			// arrive, and closing the stream drops the connection. BodyHandlers.discarding() would still
			// pull the whole body off the socket (a multi-GB answer would hold the check until the timeout).
			CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request,
					HttpResponse.BodyHandlers.ofInputStream());
			HttpResponse<InputStream> response;
			try {
				// The request timeout already fires inside the client; this is the safety net.
				response = future.get(properties.requestTimeout().plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException ex) {
				future.cancel(true);
				return CheckResult.failed("Request timed out after " + human(properties.requestTimeout()));
			}
			catch (ExecutionException ex) {
				return CheckResult.failed(describe(ex.getCause() == null ? ex : ex.getCause(), current));
			}
			catch (InterruptedException ex) {
				future.cancel(true);
				throw ex;
			}

			closeQuietly(response.body());
			int status = response.statusCode();
			Optional<String> location = response.headers().firstValue("Location");
			if (REDIRECT_STATUSES.contains(status) && location.isPresent()) {
				URI next;
				try {
					next = redirectTarget(current, location.get());
				}
				catch (RedirectRejectedException ex) {
					return CheckResult.failed(ex.getMessage());
				}
				if (!visited.add(next.toString())) {
					return CheckResult.failed("Redirect loop detected at " + safeUrl(next));
				}
				if (++redirects > properties.maxRedirects()) {
					return CheckResult.failed("Too many redirects (max " + properties.maxRedirects() + ")");
				}
				current = next;
				continue;
			}

			int latencyMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
			Instant certExpiresAt = response.sslSession().flatMap(certificates::expiryOf).orElse(null);
			if (status < 400) {
				return CheckResult.succeeded(status, latencyMs, certExpiresAt);
			}
			return CheckResult.failed(status, latencyMs, "HTTP " + status, certExpiresAt);
		}
	}

	private static void closeQuietly(InputStream body) {
		try {
			body.close();
		}
		catch (IOException ex) {
			// the connection is being dropped on purpose; nothing to report
		}
	}

	/** Resolves, validates and canonicalizes the Location of a redirect. */
	private static URI redirectTarget(URI from, String location) {
		URI target;
		try {
			target = from.resolve(location.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new RedirectRejectedException("Redirect to an invalid location");
		}
		String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new RedirectRejectedException("Redirect to an unsupported scheme");
		}
		if (target.getRawAuthority() != null && target.getRawAuthority().contains("@")) {
			throw new RedirectRejectedException("Redirect target contains credentials");
		}
		try {
			return SiteUrlNormalizer.normalize(target.toString());
		}
		catch (InvalidSiteUrlException ex) {
			throw new RedirectRejectedException("Redirect to an invalid location");
		}
	}

	private String describe(Throwable failure, URI uri) {
		List<Throwable> chain = chainOf(failure);

		for (Throwable t : chain) {
			if (t instanceof CertificateExpiredException) {
				return "SSL error: the certificate has expired";
			}
			if (t instanceof CertificateNotYetValidException) {
				return "SSL error: the certificate is not valid yet";
			}
		}
		String text = chain.stream().map(Throwable::getMessage).filter(m -> m != null)
				.reduce("", (a, b) -> a + " | " + b).toLowerCase(Locale.ROOT);
		if (chain.stream().anyMatch(t -> t instanceof SSLException)) {
			if (text.contains("notafter") || text.contains("validity check failed") || text.contains("expired")) {
				return "SSL error: the certificate has expired";
			}
			if (text.contains("no subject alternative") || text.contains("no name matching")) {
				return "SSL error: the certificate does not match the host name";
			}
			if (text.contains("pkix path") || text.contains("unable to find valid certification path")
					|| text.contains("trust anchor") || text.contains("self-signed")) {
				return "SSL error: the certificate is not trusted";
			}
			return "SSL error: TLS handshake failed";
		}
		if (chain.stream().anyMatch(t -> t instanceof HttpConnectTimeoutException)) {
			return "Connection timed out after " + human(properties.connectTimeout());
		}
		if (chain.stream().anyMatch(t -> t instanceof HttpTimeoutException)) {
			return "Request timed out after " + human(properties.requestTimeout());
		}
		// The HTTP client reports an unresolvable name as a ConnectException caused by UnresolvedAddressException.
		if (chain.stream().anyMatch(t -> t instanceof UnknownHostException || t instanceof UnresolvedAddressException)) {
			return "DNS error: could not resolve host " + uri.getHost();
		}
		if (chain.stream().anyMatch(t -> t instanceof ConnectException)) {
			return "Connection failed: refused or unreachable";
		}
		Throwable root = chain.get(chain.size() - 1);
		return "Request failed: " + root.getClass().getSimpleName();
	}

	private static List<Throwable> chainOf(Throwable failure) {
		List<Throwable> chain = new ArrayList<>();
		for (Throwable t = failure; t != null && chain.size() < 10 && !chain.contains(t); t = t.getCause()) {
			chain.add(t);
		}
		return chain;
	}

	/** {@code scheme://host[:port]/path}: never the query string, fragment or user info. */
	private static String safeUrl(URI uri) {
		StringBuilder out = new StringBuilder().append(uri.getScheme()).append("://").append(uri.getHost());
		if (uri.getPort() != -1) {
			out.append(':').append(uri.getPort());
		}
		if (uri.getRawPath() != null) {
			out.append(uri.getRawPath());
		}
		return out.toString();
	}

	private static String human(Duration duration) {
		long millis = duration.toMillis();
		return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
	}

	private static final class RedirectRejectedException extends RuntimeException {

		RedirectRejectedException(String message) {
			super(message, null, false, false);
		}
	}
}
