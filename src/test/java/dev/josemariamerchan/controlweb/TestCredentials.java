package dev.josemariamerchan.controlweb;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Credentials used by the test suite. They are generated randomly once per test JVM, so no secret
 * (not even a fake-looking one) is committed to the repository, and a developer's real {@code .env}
 * can never leak into a test run. See {@link TestSecretsEnvironmentPostProcessor}.
 */
public final class TestCredentials {

	private static final SecureRandom RANDOM = new SecureRandom();

	public static final String JWT_SECRET = randomBase64(32);
	public static final String OTHER_JWT_SECRET = randomBase64(32);
	/** A random, well-formed (but fake) Telegram bot token: 9 digits, a colon and 35 URL-safe characters. */
	public static final String TELEGRAM_TOKEN = "123456789:" + randomBase64(27).replace('+', '-').replace('/', '_').replace("=", "");
	/** The secret half of the token (after the colon): it is what really must never leak, in any encoding. */
	public static final String TELEGRAM_TOKEN_SECRET = TELEGRAM_TOKEN.substring(TELEGRAM_TOKEN.indexOf(':') + 1);
	public static final String ADMIN_EMAIL = "admin@test.local";
	public static final String ADMIN_PASSWORD = "pw-" + randomBase64(18);

	private TestCredentials() {
	}

	/**
	 * Every form the bot token can take in a log line or message: as is, with the colon percent-encoded (what a
	 * URL shows, e.g. in "I/O error on POST request for ..."), and just its secret half.
	 */
	public static java.util.List<String> telegramTokenForms() {
		return java.util.List.of(TELEGRAM_TOKEN, TELEGRAM_TOKEN.replace(":", "%3A"), TELEGRAM_TOKEN.replace(":", "%3a"),
				TELEGRAM_TOKEN_SECRET);
	}

	public static boolean leaksTelegramToken(String text) {
		return telegramTokenForms().stream().anyMatch(text::contains);
	}

	public static String randomBase64(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getEncoder().encodeToString(buffer);
	}

	static Map<String, Object> asProperties() {
		return Map.of(
				"app.jwt.secret", JWT_SECRET,
				"app.admin.email", ADMIN_EMAIL,
				"app.admin.password", ADMIN_PASSWORD);
	}
}
