package dev.josemariamerchan.controlweb.common.text;

import java.util.regex.Pattern;

/**
 * Cleans text before it is stored or logged. A URL may carry secrets in its query string
 * ({@code ?token=...}) or credentials ({@code user:pass@}), so both are removed; control characters
 * and line breaks are flattened and the length is capped.
 */
public final class MessageSanitizer {

	public static final int MAX_LENGTH = 500;

	private static final Pattern USER_INFO = Pattern.compile("(https?://)[^/\\s@'\"<>]+@", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("(https?://[^\\s?#'\"<>]*)[?#][^\\s'\"<>]*",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]+");

	private MessageSanitizer() {
	}

	public static String sanitize(String message) {
		if (message == null) {
			return null;
		}
		String clean = USER_INFO.matcher(message).replaceAll("$1");
		clean = QUERY_OR_FRAGMENT.matcher(clean).replaceAll("$1");
		clean = WHITESPACE_OR_CONTROL.matcher(clean).replaceAll(" ").strip();
		if (clean.length() > MAX_LENGTH) {
			clean = clean.substring(0, MAX_LENGTH - 1) + "…";
		}
		return clean;
	}
}
