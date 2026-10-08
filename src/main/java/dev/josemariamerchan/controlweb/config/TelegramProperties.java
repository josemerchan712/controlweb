package dev.josemariamerchan.controlweb.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Telegram alerts. The bot token is a SECRET: it is only ever held here and in the
 * Telegram notifier, {@link #toString()} masks it, and nothing may log it.
 *
 * @param botToken       env var {@code TELEGRAM_BOT_TOKEN}; blank means "not set"
 * @param chatId         env var {@code TELEGRAM_CHAT_ID}; blank means "not set"
 * @param apiBaseUrl     Telegram Bot API address; must be https (plain http only for loopback, used by tests)
 * @param connectTimeout maximum time to open the connection
 * @param readTimeout    maximum time to wait for the response
 * @param maxRetries     retries after the first attempt, for network errors, 5xx and a short 429
 * @param retryDelay     short pause between retries
 * @param maxRetryAfter  a 429 asking to wait longer than this is not retried: the alert is dropped
 */
@ConfigurationProperties("app.telegram")
public record TelegramProperties(
		String botToken,
		String chatId,
		@DefaultValue("https://api.telegram.org") String apiBaseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout,
		@DefaultValue("2") int maxRetries,
		@DefaultValue("500ms") Duration retryDelay,
		@DefaultValue("10s") Duration maxRetryAfter) {

	private static final Pattern TOKEN_SHAPE = Pattern.compile("\\d{3,}:[A-Za-z0-9_-]{20,}");
	private static final Pattern CHAT_ID_SHAPE = Pattern.compile("-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{4,}");

	public TelegramProperties {
		botToken = blankToNull(botToken);
		chatId = blankToNull(chatId);
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("app.telegram.connect-timeout must be positive");
		}
		if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("app.telegram.read-timeout must be positive");
		}
		if (maxRetries < 0 || maxRetries > 5) {
			throw new IllegalArgumentException("app.telegram.max-retries must be between 0 and 5");
		}
		if (retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(10)) > 0) {
			throw new IllegalArgumentException("app.telegram.retry-delay must be between 0 and 10s");
		}
		if (maxRetryAfter == null || maxRetryAfter.isNegative()) {
			throw new IllegalArgumentException("app.telegram.max-retry-after must not be negative");
		}
		apiBaseUrl = validatedBaseUrl(apiBaseUrl);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** The token would be sent to this address, so it must not be a plain-http one (except loopback, for tests). */
	private static String validatedBaseUrl(String value) {
		URI uri;
		try {
			uri = URI.create(value == null ? "" : value.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("app.telegram.api-base-url is not a valid URL");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
		if (host.isEmpty() || !(scheme.equals("https") || (scheme.equals("http") && loopback))) {
			throw new IllegalArgumentException("app.telegram.api-base-url must be an https URL");
		}
		String text = uri.toString();
		return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
	}

	public boolean hasToken() {
		return botToken != null;
	}

	public boolean hasChatId() {
		return chatId != null;
	}

	public boolean tokenLooksValid() {
		return botToken != null && TOKEN_SHAPE.matcher(botToken).matches();
	}

	public boolean chatIdLooksValid() {
		return chatId != null && CHAT_ID_SHAPE.matcher(chatId).matches();
	}

	@Override
	public String toString() {
		return "TelegramProperties[botToken=" + (hasToken() ? "<redacted>" : "<not set>") + ", chatId="
				+ (hasChatId() ? "<redacted>" : "<not set>") + ", apiBaseUrl=" + apiBaseUrl + ", connectTimeout="
				+ connectTimeout + ", readTimeout=" + readTimeout + ", maxRetries=" + maxRetries + "]";
	}
}
