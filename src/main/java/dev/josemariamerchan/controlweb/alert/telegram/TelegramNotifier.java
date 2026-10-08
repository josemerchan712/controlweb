package dev.josemariamerchan.controlweb.alert.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import dev.josemariamerchan.controlweb.alert.Notifier;
import dev.josemariamerchan.controlweb.alert.SendResult;
import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;
import dev.josemariamerchan.controlweb.config.TelegramProperties;

/**
 * Sends alerts through the Telegram Bot API ({@code sendMessage}, HTML parse mode).
 *
 * <h2>The token never leaks</h2>
 * The Bot API wants the token IN THE URL PATH, and URLs end up in log lines and exception messages.
 * So the request is built for {@code /sendMessage} only, and an interceptor prepends {@code /bot<token>}
 * at the very last moment, after Spring has already recorded the URI it uses for its logs, metrics and
 * exception messages. Spring therefore never knows the token. On top of that this class never logs an
 * exception's message, only its own safe classification.
 *
 * <h2>Retries (bounded)</h2>
 * Network errors and 5xx are retried up to {@code max-retries} times after a short pause. A 429 is
 * retried only if Telegram asks for a short wait ({@code retry_after} within {@code max-retry-after});
 * otherwise the alert is dropped. Any other 4xx (wrong token, wrong chat id, malformed message) is
 * never retried: one clear warning is logged.
 */
public class TelegramNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

	private static final Pattern RETRY_AFTER_BODY = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
	private static final Pattern DESCRIPTION_BODY = Pattern.compile("\"description\"\\s*:\\s*\"([^\"]{0,160})");

	/** Waits between attempts; replaced in tests so they do not really sleep. */
	@FunctionalInterface
	public interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	private sealed interface Attempt {

		record Delivered() implements Attempt {
		}

		record Retry(Duration delay, String reason) implements Attempt {
		}

		record GiveUp(String reason) implements Attempt {
		}
	}

	private final RestClient client;
	private final TelegramProperties properties;
	private final Sleeper sleeper;

	/** Production constructor: real timeouts, no redirects (a redirect could carry the token elsewhere). */
	public static TelegramNotifier create(TelegramProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.version(HttpClient.Version.HTTP_1_1)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return new TelegramNotifier(RestClient.builder().requestFactory(requestFactory), properties, Thread::sleep);
	}

	public TelegramNotifier(RestClient.Builder builder, TelegramProperties properties, Sleeper sleeper) {
		if (!properties.hasToken() || !properties.hasChatId()) {
			throw new IllegalArgumentException("TelegramNotifier needs both a bot token and a chat id");
		}
		this.properties = properties;
		this.sleeper = sleeper;
		this.client = builder.baseUrl(properties.apiBaseUrl())
				.requestInterceptor(new BotTokenPathInterceptor(properties.botToken()))
				.build();
	}

	@Override
	public SendResult send(String htmlMessage) {
		try {
			return sendWithRetries(htmlMessage);
		}
		catch (RuntimeException ex) {
			// Last resort. Only the type is logged: a message could carry the request URL.
			log.warn("Telegram alert not delivered: unexpected {}", ex.getClass().getSimpleName());
			return SendResult.failed("Unexpected error while sending (" + ex.getClass().getSimpleName() + ")");
		}
	}

	private SendResult sendWithRetries(String htmlMessage) {
		int attempts = 0;
		while (true) {
			attempts++;
			Attempt attempt = attemptOnce(htmlMessage);
			if (attempt instanceof Attempt.Delivered) {
				log.debug("Telegram alert delivered (attempt {})", attempts);
				return SendResult.sent();
			}
			if (attempt instanceof Attempt.GiveUp giveUp) {
				log.warn("Telegram alert not delivered: {}", giveUp.reason());
				return SendResult.failed(giveUp.reason());
			}
			Attempt.Retry retry = (Attempt.Retry) attempt;
			if (attempts > properties.maxRetries()) {
				String reason = retry.reason() + " (gave up after " + attempts + " attempts)";
				log.warn("Telegram alert not delivered: {}", reason);
				return SendResult.failed(reason);
			}
			log.debug("Telegram alert attempt {} failed ({}); retrying in {}", attempts, retry.reason(), retry.delay());
			try {
				sleeper.sleep(retry.delay());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				log.warn("Telegram alert not delivered: interrupted while waiting to retry");
				return SendResult.failed("Interrupted while waiting to retry");
			}
		}
	}

	private Attempt attemptOnce(String htmlMessage) {
		try {
			ResponseEntity<Void> response = client.post()
					.uri("/sendMessage")
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of(
							"chat_id", properties.chatId(),
							"text", htmlMessage,
							"parse_mode", "HTML",
							"disable_web_page_preview", true))
					.retrieve()
					.toBodilessEntity();
			if (response.getStatusCode().is2xxSuccessful()) {
				return new Attempt.Delivered();
			}
			// Only 4xx/5xx raise exceptions; anything else that is not a 2xx (e.g. a redirect, which is never followed) is no success.
			return new Attempt.GiveUp("Unexpected response from Telegram (HTTP " + response.getStatusCode().value() + ")");
		}
		catch (RestClientResponseException ex) {
			return classify(ex);
		}
		catch (RestClientException ex) {
			// Connection refused, DNS failure, timeouts, resets... Never log ex: its message holds the URI.
			return new Attempt.Retry(properties.retryDelay(), describeNetworkFailure(ex));
		}
	}

	private Attempt classify(RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		String body = ex.getResponseBodyAsString();
		if (status == 429) {
			return classifyTooManyRequests(ex, body);
		}
		if (status >= 500) {
			return new Attempt.Retry(properties.retryDelay(), "Telegram server error (HTTP " + status + ")");
		}
		String telegramSays = telegramDescription(body);
		return new Attempt.GiveUp("Telegram rejected the alert (HTTP " + status + "): check TELEGRAM_BOT_TOKEN and "
				+ "TELEGRAM_CHAT_ID" + (telegramSays == null ? "" : ". Telegram says: " + telegramSays));
	}

	private Attempt classifyTooManyRequests(RestClientResponseException ex, String body) {
		Long retryAfterSeconds = retryAfterSeconds(ex, body);
		if (retryAfterSeconds == null) {
			return new Attempt.GiveUp("Telegram is rate limiting (HTTP 429) without saying when to retry; alert dropped");
		}
		Duration wait = Duration.ofSeconds(retryAfterSeconds);
		if (wait.compareTo(properties.maxRetryAfter()) > 0) {
			return new Attempt.GiveUp("Telegram is rate limiting (HTTP 429) and asks to wait " + retryAfterSeconds
					+ " s, longer than the " + properties.maxRetryAfter().toSeconds() + " s limit; alert dropped");
		}
		return new Attempt.Retry(wait, "Telegram is rate limiting (HTTP 429, retry_after " + retryAfterSeconds + " s)");
	}

	private static Long retryAfterSeconds(RestClientResponseException ex, String body) {
		Matcher matcher = RETRY_AFTER_BODY.matcher(body == null ? "" : body);
		if (matcher.find()) {
			return Long.parseLong(matcher.group(1));
		}
		String header = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst("Retry-After");
		if (header != null && header.trim().matches("\\d{1,9}")) {
			return Long.parseLong(header.trim());
		}
		return null;
	}

	private static String telegramDescription(String body) {
		Matcher matcher = DESCRIPTION_BODY.matcher(body == null ? "" : body);
		return matcher.find() ? MessageSanitizer.sanitize(matcher.group(1)) : null;
	}

	private static String describeNetworkFailure(RestClientException ex) {
		Throwable cause = ex.getCause();
		String kind = cause == null ? ex.getClass().getSimpleName() : cause.getClass().getSimpleName();
		if (kind.contains("Timeout")) {
			return "Timed out talking to Telegram";
		}
		return "Network error talking to Telegram (" + kind + ")";
	}

	/**
	 * Prepends {@code /bot<token>} to the path of the outgoing request, as the last step before it is
	 * sent. Spring has already captured the token-free URI for logs and exception messages.
	 */
	private static final class BotTokenPathInterceptor implements ClientHttpRequestInterceptor {

		private final String prefix;

		BotTokenPathInterceptor(String token) {
			this.prefix = "/bot" + token;
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			URI original = request.getURI();
			URI withToken = URI.create(original.getScheme() + "://" + original.getRawAuthority() + prefix
					+ original.getRawPath());
			return execution.execute(new HttpRequestWrapper(request) {
				@Override
				public URI getURI() {
					return withToken;
				}
			}, body);
		}
	}
}
