package dev.josemariamerchan.controlweb.common.text;

import java.util.regex.Pattern;

/**
 * Cleans text before it is stored or logged. A URL may carry secrets in its query string
 * ({@code ?token=...}) or credentials ({@code user:pass@}), so both are removed; a Telegram bot token
 * (which travels in the path of the Bot API, {@code /bot<id>:<secret>/...}) is masked; control
 * characters and line breaks are flattened and the length is capped.
 */
public final class MessageSanitizer {

	public static final int MAX_LENGTH = 500;

	private static final Pattern USER_INFO = Pattern.compile("(https?://)[^/\\s@'\"<>]+@", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("(https?://[^\\s?#'\"<>]*)[?#][^\\s'\"<>]*",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern TELEGRAM_BOT_TOKEN = Pattern.compile("bot\\d+(?::|%3A)[A-Za-z0-9_-]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]+");

	private MessageSanitizer() {
	}

	public static String sanitize(String message) {
		if (message == null) {
			return null;
		}
		// Order matters: URLs lose their user info and query first (the placeholder below must not get in the
		// way of recognising a URL), then any bot token left is masked.
		String clean = USER_INFO.matcher(message).replaceAll("$1");
		clean = QUERY_OR_FRAGMENT.matcher(clean).replaceAll("$1");
		clean = TELEGRAM_BOT_TOKEN.matcher(clean).replaceAll("bot[redacted]");
		clean = WHITESPACE_OR_CONTROL.matcher(clean).replaceAll(" ").strip();
		if (clean.length() > MAX_LENGTH) {
			clean = clean.substring(0, MAX_LENGTH - 1) + "…";
		}
		return clean;
	}
}
