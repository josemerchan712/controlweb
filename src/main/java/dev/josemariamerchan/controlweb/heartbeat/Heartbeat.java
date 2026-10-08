package dev.josemariamerchan.controlweb.heartbeat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.HeartbeatProperties;
import jakarta.annotation.PreDestroy;

/**
 * Tells an external service "the monitor is alive". {@link #beat()} is called after every scheduler cycle that really
 * ran; if the cycle cannot run (database down, application stopped) no ping is sent and the external service notices the
 * silence.
 *
 * <p>It can never hurt the monitor: the ping is sent on its own thread, at most one is in flight, at most one is sent per
 * {@code min-interval} (by the injected Clock), a redirect is a failure and is not followed, and nothing escapes.
 * Only the state CHANGES are logged (one WARN when pings start failing, one INFO when they recover), and a log line
 * never contains the URL or an exception message (both could reveal the secret ping URL): only the host and the kind of
 * failure.
 */
@Component
public class Heartbeat {

	private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

	private final HeartbeatProperties properties;
	private final Clock clock;
	private final HttpClient client;
	private final URI uri;
	private final ExecutorService executor;
	private final AtomicBoolean inFlight = new AtomicBoolean(false);
	private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();
	private final AtomicBoolean failing = new AtomicBoolean(false);
	private final AtomicLong finished = new AtomicLong();

	public Heartbeat(HeartbeatProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		if (properties.enabled()) {
			this.uri = URI.create(properties.url());
			this.client = HttpClient.newBuilder()
					.connectTimeout(properties.timeout())
					.followRedirects(HttpClient.Redirect.NEVER)
					.version(HttpClient.Version.HTTP_1_1)
					.build();
			this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("heartbeat-", 0).factory());
			log.info("Heartbeat enabled: at most one ping per {} to {}", properties.minInterval(), properties.host());
		}
		else {
			this.uri = null;
			this.client = null;
			this.executor = null;
		}
	}

	/** Sends a ping unless the heartbeat is off, one is already in flight, or the last attempt was too recent. */
	public void beat() {
		if (!properties.enabled()) {
			return;
		}
		Instant now = clock.instant();
		Instant previous = lastAttempt.get();
		if (previous != null && now.isBefore(previous.plus(properties.minInterval()))) {
			return;
		}
		if (!inFlight.compareAndSet(false, true)) {
			return;
		}
		lastAttempt.set(now);
		try {
			executor.execute(this::ping);
		}
		catch (RejectedExecutionException ex) {
			inFlight.set(false); // shutting down
		}
	}

	/** How many pings have ended (successfully or not). For tests and diagnostics. */
	public long pingsFinished() {
		return finished.get();
	}

	@PreDestroy
	public void shutdown() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	private void ping() {
		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
					.GET()
					.timeout(properties.timeout())
					.header("User-Agent", "ControlWeb-Heartbeat/1.0")
					.build();
			int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status >= 200 && status < 300) {
				recovered();
			}
			else {
				failed("HTTP " + status);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (Exception ex) {
			// The class name only: the message of an HTTP client exception can contain the full URL.
			failed(ex.getClass().getSimpleName());
		}
		finally {
			inFlight.set(false);
			finished.incrementAndGet();
		}
	}

	private void recovered() {
		if (failing.compareAndSet(true, false)) {
			log.info("Heartbeat pings to {} work again", properties.host());
		}
	}

	private void failed(String reason) {
		if (failing.compareAndSet(false, true)) {
			log.warn("Heartbeat ping to {} failed ({}); the external monitor will raise the alarm if this lasts. "
					+ "Further failures are not logged until a ping works again.", properties.host(), reason);
		}
	}
}
