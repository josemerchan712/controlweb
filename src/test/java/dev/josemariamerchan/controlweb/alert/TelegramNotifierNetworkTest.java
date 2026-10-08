package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.alert.SendResult.Status;
import dev.josemariamerchan.controlweb.alert.telegram.TelegramNotifier;
import dev.josemariamerchan.controlweb.config.TelegramProperties;
import dev.josemariamerchan.controlweb.monitoring.TestServer;

/**
 * The production notifier ({@link TelegramNotifier#create}) over a real socket: real timeouts, real
 * connection errors, and the guarantee that the bot token never shows up in any log.
 */
class TelegramNotifierNetworkTest {

	private static final String TOKEN = TestCredentials.TELEGRAM_TOKEN;
	private static final String MESSAGE = "<b>Incidencia abierta</b>";

	private final List<TestServer> servers = new ArrayList<>();

	@AfterEach
	void tearDown() {
		servers.forEach(TestServer::close);
	}

	private TelegramProperties properties(String baseUrl, Duration connect, Duration read, int maxRetries) {
		return new TelegramProperties(TOKEN, "123456", baseUrl, connect, read, maxRetries, Duration.ZERO, Duration.ofSeconds(10));
	}

	private TestServer server() throws Exception {
		TestServer server = TestServer.http();
		servers.add(server);
		return server;
	}

	private static String closedPortBaseUrl() throws Exception {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return "http://localhost:" + socket.getLocalPort();
		}
	}

	@Test
	@DisplayName("a real delivery: POST /bot<token>/sendMessage with a JSON body")
	void realDelivery() throws Exception {
		TestServer telegram = server();
		telegram.respond("/", 200);

		SendResult result = TelegramNotifier
				.create(properties("http://localhost:" + telegram.port(), Duration.ofSeconds(3), Duration.ofSeconds(5), 2))
				.send(MESSAGE);

		assertThat(result.isSent()).isTrue();
		assertThat(telegram.requests()).singleElement().satisfies(request -> {
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.path()).isEqualTo("/bot" + TOKEN + "/sendMessage");
			assertThat(request.header("Content-Type")).startsWith("application/json");
		});
	}

	@Test
	@DisplayName("the read timeout is real: a Telegram that never answers fails fast and is retried the configured times")
	void readTimeout() throws Exception {
		TestServer telegram = server();
		telegram.on("/", exchange -> {
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		TelegramNotifier notifier = TelegramNotifier
				.create(properties("http://localhost:" + telegram.port(), Duration.ofSeconds(3), Duration.ofMillis(300), 1));

		long started = System.nanoTime();
		SendResult result = notifier.send(MESSAGE);
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).startsWith("Timed out talking to Telegram").contains("gave up after 2 attempts");
		assertThat(elapsedMs).as("2 attempts of ~300 ms, nowhere near the 5 s the server wanted").isLessThan(3500);
		assertThat(telegram.requests()).hasSize(2);
	}

	@Test
	@DisplayName("a 401 from the real wire is one request and one clear failure")
	void unauthorizedIsNotRetried() throws Exception {
		TestServer telegram = server();
		telegram.respond("/", 401);

		SendResult result = TelegramNotifier
				.create(properties("http://localhost:" + telegram.port(), Duration.ofSeconds(3), Duration.ofSeconds(5), 2))
				.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("HTTP 401");
		assertThat(telegram.requests()).hasSize(1);
	}

	@Test
	@DisplayName("redirects are never followed: the token must not travel to another address")
	void redirectsAreNotFollowed() throws Exception {
		TestServer telegram = server();
		telegram.redirect("/", 302, "http://localhost:" + telegram.port() + "/elsewhere");
		telegram.respond("/elsewhere", 200);

		SendResult result = TelegramNotifier
				.create(properties("http://localhost:" + telegram.port(), Duration.ofSeconds(3), Duration.ofSeconds(5), 2))
				.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(telegram.requests()).extracting(TestServer.RecordedRequest::path).containsExactly("/bot" + TOKEN + "/sendMessage");
	}

	@Test
	@DisplayName("a network failure leaves the token out of EVERY log line, exception message and stack trace (all loggers, TRACE)")
	void tokenIsNeverLoggedOnNetworkFailure() throws Exception {
		TestServer hanging = server();
		hanging.on("/", exchange -> {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		TestServer unauthorized = server();
		unauthorized.respond("/", 401);
		TestServer broken = server();
		broken.respond("/", 503);

		try (LogCapture everything = LogCapture.everything(Level.TRACE)) {
			List<SendResult> results = new ArrayList<>();
			// connection refused (nothing listens), several retries
			results.add(TelegramNotifier.create(properties(closedPortBaseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1), 2)).send(MESSAGE));
			// read timeout
			results.add(TelegramNotifier.create(properties("http://localhost:" + hanging.port(), Duration.ofSeconds(1), Duration.ofMillis(200), 1)).send(MESSAGE));
			// rejected credentials
			results.add(TelegramNotifier.create(properties("http://localhost:" + unauthorized.port(), Duration.ofSeconds(1), Duration.ofSeconds(1), 2)).send(MESSAGE));
			// server errors
			results.add(TelegramNotifier.create(properties("http://localhost:" + broken.port(), Duration.ofSeconds(1), Duration.ofSeconds(1), 2)).send(MESSAGE));

			assertThat(results).allMatch(result -> result.status() == Status.FAILED);
			assertThat(results).extracting(SendResult::detail).noneMatch(TestCredentials::leaksTelegramToken);
			// The fake Telegram runs in this JVM and the JDK's HTTP server logs every request line (token included
			// because it is in the path): that is the SERVER's logging, which real Telegram does in its own machines.
			List<String> everythingLogged = everything.fullTextExcluding("com.sun.net.httpserver", "sun.net.httpserver");
			assertThat(everythingLogged).as("the capture really saw the notifier's own warnings")
					.anyMatch(text -> text.contains("Telegram alert not delivered"));
			assertThat(everythingLogged).as("and plenty of other client-side logging").hasSizeGreaterThan(3);
			assertThat(everythingLogged).as("the token, in any of its encodings, is in no log line")
					.noneMatch(TestCredentials::leaksTelegramToken);
			assertThat(everythingLogged).noneMatch(text -> text.contains("/bot" + TOKEN.substring(0, 12)));
		}
	}
}
