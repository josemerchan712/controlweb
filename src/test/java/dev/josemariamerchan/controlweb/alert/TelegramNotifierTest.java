package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Level;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.alert.SendResult.Status;
import dev.josemariamerchan.controlweb.alert.telegram.TelegramNotifier;
import dev.josemariamerchan.controlweb.config.TelegramProperties;

/** The notifier against a mocked Telegram: what is sent, and what happens on every kind of failure. */
class TelegramNotifierTest {

	private static final String TOKEN = TestCredentials.TELEGRAM_TOKEN;
	private static final String SEND_URL = "https://api.telegram.org/bot" + TOKEN + "/sendMessage";
	private static final String MESSAGE = "🔴 <b>Incidencia abierta</b>\n<b>Sitio:</b> Blog &amp; Co";
	private static final String TOO_MANY = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after %d\","
			+ "\"parameters\":{\"retry_after\":%d}}";

	private final List<Duration> sleeps = new ArrayList<>();
	private MockRestServiceServer server;
	private LogCapture logs;

	@BeforeEach
	void captureLogs() {
		logs = LogCapture.of(TelegramNotifier.class, Level.DEBUG);
	}

	@AfterEach
	void closeLogs() {
		logs.close();
	}

	private TelegramProperties properties(int maxRetries) {
		return new TelegramProperties(TOKEN, "123456", "https://api.telegram.org", Duration.ofSeconds(3),
				Duration.ofSeconds(5), maxRetries, Duration.ofMillis(500), Duration.ofSeconds(10));
	}

	private TelegramNotifier notifier(int maxRetries) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new TelegramNotifier(builder, properties(maxRetries), sleeps::add);
	}

	private void expectOk() {
		server.expect(requestTo(SEND_URL)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
	}

	private void expectStatus(int status, String body) {
		server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
	}

	// ---- the request ----

	@Test
	@DisplayName("sends one POST to /bot<token>/sendMessage with chat id, the HTML text and parse_mode=HTML")
	void sendsTheMessage() {
		TelegramNotifier notifier = notifier(2);
		server.expect(requestTo(SEND_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.chat_id").value("123456"))
				.andExpect(jsonPath("$.text").value(MESSAGE))
				.andExpect(jsonPath("$.parse_mode").value("HTML"))
				.andExpect(jsonPath("$.disable_web_page_preview").value(true))
				.andRespond(withSuccess("{\"ok\":true,\"result\":{}}", MediaType.APPLICATION_JSON));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.isSent()).isTrue();
		server.verify();
		assertThat(sleeps).isEmpty();
	}

	@Test
	@DisplayName("a channel username works as chat id")
	void channelChatId() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		TelegramProperties properties = new TelegramProperties(TOKEN, "@my_alerts", "https://api.telegram.org",
				Duration.ofSeconds(3), Duration.ofSeconds(5), 2, Duration.ofMillis(500), Duration.ofSeconds(10));
		server.expect(requestTo(SEND_URL)).andExpect(jsonPath("$.chat_id").value("@my_alerts"))
				.andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

		assertThat(new TelegramNotifier(builder, properties, sleeps::add).send(MESSAGE).isSent()).isTrue();
		server.verify();
	}

	@Test
	@DisplayName("it needs both a token and a chat id")
	void requiresBothValues() {
		TelegramProperties noChat = new TelegramProperties(TOKEN, null, "https://api.telegram.org", Duration.ofSeconds(3),
				Duration.ofSeconds(5), 2, Duration.ofMillis(500), Duration.ofSeconds(10));

		assertThatThrownBy(() -> new TelegramNotifier(RestClient.builder(), noChat, sleeps::add))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- retries: server errors and network errors ----

	@Test
	@DisplayName("a 5xx is retried after a short pause and the alert goes out when Telegram recovers")
	void retriesServerErrors() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(500, "{\"ok\":false,\"error_code\":500}");
		expectStatus(502, "bad gateway");
		expectOk();

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.isSent()).isTrue();
		server.verify();
		assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
	}

	@Test
	@DisplayName("persistent 5xx: exactly 1 + 2 attempts, then it gives up with a single warning")
	void givesUpAfterTwoRetries() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(503, "unavailable");
		expectStatus(503, "unavailable");
		expectStatus(503, "unavailable");

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("HTTP 503").contains("gave up after 3 attempts");
		server.verify(); // a 4th request would have failed the mock
		assertThat(sleeps).hasSize(2);
		assertThat(logs.events().stream().filter(event -> event.getLevel() == Level.WARN)).hasSize(1);
	}

	@Test
	@DisplayName("the number of retries is configurable (0 = a single attempt)")
	void retriesAreConfigurable() {
		TelegramNotifier notifier = notifier(0);
		expectStatus(500, "oops");

		assertThat(notifier.send(MESSAGE).status()).isEqualTo(Status.FAILED);

		server.verify();
		assertThat(sleeps).isEmpty();
	}

	@Test
	@DisplayName("network errors (timeout, connection refused) are retried too, and reported without internals")
	void retriesNetworkErrors() {
		TelegramNotifier notifier = notifier(2);
		server.expect(requestTo(SEND_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
		server.expect(requestTo(SEND_URL)).andRespond(withException(new ConnectException("Connection refused")));
		expectOk();

		assertThat(notifier.send(MESSAGE).isSent()).isTrue();
		server.verify();
		assertThat(sleeps).hasSize(2);
	}

	@Test
	@DisplayName("a persistent timeout fails with a clear 'timed out' detail")
	void persistentTimeout() {
		TelegramNotifier notifier = notifier(1);
		server.expect(requestTo(SEND_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
		server.expect(requestTo(SEND_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).startsWith("Timed out talking to Telegram").contains("gave up after 2 attempts");
	}

	@Test
	@DisplayName("a connection failure is described by its type only")
	void connectionFailureDetail() {
		TelegramNotifier notifier = notifier(0);
		server.expect(requestTo(SEND_URL)).andRespond(withException(new ConnectException("Connection refused")));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.detail()).startsWith("Network error talking to Telegram (ConnectException)");
	}

	// ---- 429 ----

	@Test
	@DisplayName("429 with a short retry_after: it waits exactly that long and retries")
	void rateLimitWithShortRetryAfter() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(429, TOO_MANY.formatted(3, 3));
		expectOk();

		assertThat(notifier.send(MESSAGE).isSent()).isTrue();
		server.verify();
		assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
	}

	@Test
	@DisplayName("retry_after of exactly 10 s is still honoured")
	void retryAfterAtTheLimit() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(429, TOO_MANY.formatted(10, 10));
		expectOk();

		assertThat(notifier.send(MESSAGE).isSent()).isTrue();
		assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
	}

	@Test
	@DisplayName("429 asking for more than 10 s: the alert is dropped (one request, no waiting) and it is logged")
	void rateLimitWithLongRetryAfter() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(429, TOO_MANY.formatted(11, 11));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("429").contains("11 s").contains("alert dropped");
		server.verify();
		assertThat(sleeps).isEmpty();
		assertThat(logs.messages()).anyMatch(message -> message.contains("alert dropped"));
	}

	@Test
	@DisplayName("429 with retry_after only in the Retry-After header is honoured as well")
	void retryAfterHeader() {
		TelegramNotifier notifier = notifier(2);
		server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "4").body("slow down"));
		expectOk();

		assertThat(notifier.send(MESSAGE).isSent()).isTrue();
		assertThat(sleeps).containsExactly(Duration.ofSeconds(4));
	}

	@Test
	@DisplayName("429 without any retry hint is dropped rather than guessed")
	void rateLimitWithoutHint() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(429, "{\"ok\":false,\"error_code\":429}");

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("without saying when to retry");
		assertThat(sleeps).isEmpty();
	}

	@Test
	@DisplayName("repeated 429s do not retry forever: still at most 2 retries")
	void rateLimitRetriesAreBounded() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(429, TOO_MANY.formatted(1, 1));
		expectStatus(429, TOO_MANY.formatted(1, 1));
		expectStatus(429, TOO_MANY.formatted(1, 1));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("gave up after 3 attempts");
		server.verify();
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
	}

	// ---- 4xx: never retried ----

	@ParameterizedTest(name = "HTTP {0} is never retried")
	@ValueSource(ints = { 400, 401, 403, 404, 409 })
	void clientErrorsAreNeverRetried(int status) {
		TelegramNotifier notifier = notifier(2);
		expectStatus(status, "{\"ok\":false,\"error_code\":%d,\"description\":\"Unauthorized\"}".formatted(status));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("HTTP " + status).contains("TELEGRAM_BOT_TOKEN").contains("TELEGRAM_CHAT_ID");
		server.verify(); // one request only
		assertThat(sleeps).isEmpty();
		assertThat(logs.events().stream().filter(event -> event.getLevel() == Level.WARN))
				.as("a single clear warning").hasSize(1);
	}

	@Test
	@DisplayName("Telegram's own explanation ('chat not found') is passed on in the warning and the detail")
	void telegramDescriptionIsShown() {
		TelegramNotifier notifier = notifier(2);
		expectStatus(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.detail()).contains("Telegram says: Bad Request: chat not found");
	}

	@Test
	@DisplayName("a redirect is not a success and is never followed")
	void redirectIsNotSuccess() {
		TelegramNotifier notifier = notifier(2);
		server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://evil.example.com/steal"));

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("Unexpected response").contains("302");
		server.verify();
	}

	// ---- robustness ----

	@Test
	@DisplayName("send never throws, whatever goes wrong inside")
	void neverThrows() {
		TelegramNotifier notifier = notifier(2);
		server.expect(requestTo(SEND_URL)).andRespond(request -> {
			throw new IllegalStateException("boom while talking to https://api.telegram.org/bot" + TOKEN + "/sendMessage");
		});

		SendResult result = notifier.send(MESSAGE);

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.detail()).contains("IllegalStateException").doesNotContain(TOKEN);
		assertThat(String.join("\n", logs.fullText())).doesNotContain(TOKEN);
	}

	@Test
	@DisplayName("an interrupted wait between retries stops the sending and keeps the interrupt flag")
	void interruptedWhileWaiting() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		TelegramNotifier notifier = new TelegramNotifier(builder, properties(2), duration -> {
			throw new InterruptedException();
		});
		expectStatus(500, "oops");

		try {
			SendResult result = notifier.send(MESSAGE);

			assertThat(result.status()).isEqualTo(Status.FAILED);
			assertThat(result.detail()).containsIgnoringCase("interrupted");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	// ---- the token ----

	@Test
	@DisplayName("whatever happens, the token appears in no log line, exception text or returned detail")
	void tokenNeverAppearsAnywhere() {
		try (LogCapture everything = LogCapture.everything(Level.TRACE)) {
			List<SendResult> results = new ArrayList<>();
			TelegramNotifier notifier = notifier(2);
			expectStatus(500, "oops");
			server.expect(requestTo(SEND_URL)).andRespond(withException(new IOException("I/O error on POST request for \"" + SEND_URL + "\"")));
			expectStatus(503, "again");
			results.add(notifier.send(MESSAGE));

			notifier = notifier(2);
			expectStatus(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
			results.add(notifier.send(MESSAGE));

			notifier = notifier(2);
			expectStatus(429, TOO_MANY.formatted(30, 30));
			results.add(notifier.send(MESSAGE));

			assertThat(results).allMatch(result -> result.status() == Status.FAILED);
			assertThat(results).extracting(SendResult::detail).noneMatch(detail -> detail.contains(TOKEN));
			assertThat(String.join("\n", everything.fullText())).doesNotContain(TOKEN).doesNotContain("bot" + TOKEN);
		}
	}

	@Test
	@DisplayName("the properties never print the token or the chat id")
	void propertiesToStringIsRedacted() {
		String text = properties(2).toString();

		assertThat(text).doesNotContain(TOKEN).doesNotContain("123456").contains("<redacted>");
	}
}
