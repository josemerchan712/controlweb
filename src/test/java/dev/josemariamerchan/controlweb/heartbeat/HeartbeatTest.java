package dev.josemariamerchan.controlweb.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.TestClock;
import dev.josemariamerchan.controlweb.config.HeartbeatProperties;
import dev.josemariamerchan.controlweb.monitoring.TestServer;

/**
 * "I am alive" pings to an external service. They run on their own thread (the scheduler never waits), at most one at a
 * time and at most one per minimum interval, and nothing about a failure may escape or reveal the secret URL.
 * Time comes from a Clock fixed by hand; the external service is a local server.
 */
class HeartbeatTest {

	private static final String SECRET = "SECRET-ping-id-123";
	private static final String PATH = "/ping/" + SECRET;
	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
	private static final String[] SERVER_LOGGERS = { "com.sun.net.httpserver", "sun.net.httpserver" };

	private final TestClock clock = new TestClock();
	private TestServer server;
	private Heartbeat heartbeat;

	@BeforeEach
	void setUp() throws IOException {
		clock.fix(T0);
		server = TestServer.http();
	}

	@AfterEach
	void tearDown() {
		if (heartbeat != null) {
			heartbeat.shutdown();
		}
		server.close();
	}

	private Heartbeat heartbeat(String url, Duration timeout) {
		heartbeat = new Heartbeat(new HeartbeatProperties(url, Duration.ofSeconds(60), timeout), clock);
		return heartbeat;
	}

	private Heartbeat heartbeat() {
		return heartbeat(server.url(PATH), Duration.ofSeconds(5));
	}

	private void waitForRequests(int count) {
		await().atMost(Duration.ofSeconds(5)).until(() -> server.requests().size() >= count);
	}

	/** Gives a request that must NOT happen a moment to (wrongly) happen. */
	private static void settle() throws InterruptedException {
		Thread.sleep(300);
	}

	@Test
	@DisplayName("disabled: beating does nothing, not even a thread")
	void disabledDoesNothing() {
		Heartbeat disabled = new Heartbeat(new HeartbeatProperties("", Duration.ofSeconds(60), Duration.ofSeconds(5)), clock);

		assertThatCode(() -> {
			disabled.beat();
			disabled.beat();
		}).doesNotThrowAnyException();
		disabled.shutdown();
		assertThat(server.requests()).isEmpty();
	}

	@Test
	@DisplayName("the first beat sends ONE GET to the exact configured path, with a plain User-Agent and no credentials")
	void firstBeatIsSent() {
		server.respond(PATH, 200);

		heartbeat().beat();

		waitForRequests(1);
		TestServer.RecordedRequest request = server.requests().get(0);
		assertThat(request.method()).isEqualTo("GET");
		assertThat(request.path()).isEqualTo(PATH);
		assertThat(request.header("User-Agent")).startsWith("ControlWeb");
		assertThat(request.hasHeader("Authorization")).isFalse();
		assertThat(request.hasHeader("Cookie")).isFalse();
	}

	@Test
	@DisplayName("beats inside the minimum interval send nothing; at the boundary the next one goes out")
	void throttled() throws InterruptedException {
		server.respond(PATH, 200);
		Heartbeat beating = heartbeat();

		beating.beat();
		waitForRequests(1);
		for (int i = 0; i < 20; i++) {
			beating.beat();
		}
		clock.fix(T0.plusMillis(59_999));
		beating.beat();
		settle();
		assertThat(server.requests()).hasSize(1);

		clock.fix(T0.plusSeconds(60));
		beating.beat();
		waitForRequests(2);
		beating.beat();
		settle();
		assertThat(server.requests()).hasSize(2);
	}

	@Test
	@DisplayName("beat() never waits for the external service: a slow answer does not delay the caller")
	void beatDoesNotBlock() {
		server.on(PATH, exchange -> {
			try {
				Thread.sleep(2000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		Heartbeat beating = heartbeat();

		long start = System.nanoTime();
		beating.beat();
		long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

		assertThat(elapsedMs).isLessThan(500);
	}

	@Test
	@DisplayName("only one ping is in flight at a time: a slow one is not overtaken, and sending resumes when it ends")
	void oneInFlight() throws InterruptedException {
		server.on(PATH, exchange -> {
			try {
				Thread.sleep(1200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		Heartbeat beating = heartbeat();

		beating.beat();
		waitForRequests(1);
		clock.fix(T0.plusSeconds(61));
		beating.beat(); // the first is still waiting for its answer
		Thread.sleep(300);
		assertThat(server.requests()).hasSize(1);

		Thread.sleep(1200); // the first one is over now
		assertThat(server.requests()).as("the skipped beat must not have been queued behind the first one").hasSize(1);
		clock.fix(T0.plusSeconds(125));
		beating.beat();
		waitForRequests(2);
	}

	@Test
	@DisplayName("a failing service (HTTP 500) is not an exception: one WARN, no repetition, one INFO when it recovers")
	void failureThenRecovery() {
		AtomicInteger status = new AtomicInteger(500);
		server.on(PATH, exchange -> exchange.sendResponseHeaders(status.get(), -1));
		try (LogCapture logs = LogCapture.of(Heartbeat.class, Level.DEBUG)) {
			Heartbeat beating = heartbeat();

			for (int i = 0; i < 3; i++) {
				clock.fix(T0.plusSeconds(61L * i));
				assertThatCode(beating::beat).doesNotThrowAnyException();
				int finished = i + 1;
				await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= finished);
			}
			assertThat(levels(logs, Level.WARN)).hasSize(1);

			status.set(200);
			clock.fix(T0.plusSeconds(61L * 3));
			beating.beat();
			await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= 4);
			assertThat(logs.messages()).filteredOn(message -> message.contains("work again")).hasSize(1);

			status.set(503);
			clock.fix(T0.plusSeconds(61L * 4));
			beating.beat();
			await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= 5);
			assertThat(levels(logs, Level.WARN)).hasSize(2);
		}
	}

	private static List<ILoggingEvent> levels(LogCapture logs, Level level) {
		return logs.events().stream().filter(event -> event.getLevel() == level).toList();
	}

	@Test
	@DisplayName("a refused connection is handled the same way, and no log line contains the secret URL")
	void connectionRefusedDoesNotLeak() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		try (LogCapture logs = LogCapture.everything(Level.TRACE)) {
			Heartbeat beating = heartbeat("http://localhost:" + closedPort + PATH, Duration.ofSeconds(2));

			assertThatCode(beating::beat).doesNotThrowAnyException();
			await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= 1);

			assertThat(levels(logs, Level.WARN)).hasSize(1);
			assertThat(logs.fullTextExcluding(SERVER_LOGGERS)).noneMatch(line -> line.contains(SECRET));
			assertThat(logs.fullTextExcluding(SERVER_LOGGERS)).noneMatch(line -> line.contains(PATH));
		}
	}

	@Test
	@DisplayName("a service that answers too slowly is given up on after the timeout")
	void timeout() {
		server.on(PATH, exchange -> {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		try (LogCapture logs = LogCapture.of(Heartbeat.class, Level.DEBUG)) {
			Heartbeat beating = heartbeat(server.url(PATH), Duration.ofSeconds(1));

			beating.beat();
			await().atMost(Duration.ofMillis(2500)).until(() -> beating.pingsFinished() >= 1);

			assertThat(levels(logs, Level.WARN)).hasSize(1);
		}
	}

	@Test
	@DisplayName("a redirect is NOT followed: it counts as a failure and its target is never requested")
	void redirectIsNotFollowed() {
		server.redirect(PATH, 302, server.url("/elsewhere"));
		server.respond("/elsewhere", 200);
		try (LogCapture logs = LogCapture.of(Heartbeat.class, Level.DEBUG)) {
			Heartbeat beating = heartbeat();

			beating.beat();
			await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= 1);

			assertThat(server.requests()).extracting(TestServer.RecordedRequest::path).containsExactly(PATH);
			assertThat(levels(logs, Level.WARN)).hasSize(1);
		}
	}

	@Test
	@DisplayName("any 2xx is a success")
	void successCodes() {
		server.respond(PATH, 204);
		try (LogCapture logs = LogCapture.of(Heartbeat.class, Level.DEBUG)) {
			Heartbeat beating = heartbeat();

			beating.beat();
			await().atMost(Duration.ofSeconds(5)).until(() -> beating.pingsFinished() >= 1);

			assertThat(levels(logs, Level.WARN)).isEmpty();
		}
	}

	@Test
	@DisplayName("what is logged when it starts never contains the secret part of the URL")
	void startupLogHidesTheSecret() {
		try (LogCapture logs = LogCapture.everything(Level.TRACE)) {
			heartbeat();

			assertThat(logs.fullTextExcluding(SERVER_LOGGERS)).noneMatch(line -> line.contains(SECRET));
		}
	}
}
