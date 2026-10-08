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
	public static final String ADMIN_EMAIL = "admin@test.local";
	public static final String ADMIN_PASSWORD = "pw-" + randomBase64(18);

	private TestCredentials() {
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
