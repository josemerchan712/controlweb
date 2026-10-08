package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.auth.config.AdminProperties;
import dev.josemariamerchan.controlweb.auth.config.JwtProperties;
import dev.josemariamerchan.controlweb.config.CorsProperties;
import dev.josemariamerchan.controlweb.config.InvalidSecurityConfigurationException;
import dev.josemariamerchan.controlweb.config.InvalidSecurityConfigurationFailureAnalyzer;

/**
 * The application must refuse to start with missing or weak security settings, with a message that
 * says what to fix. No secret or password value may ever appear in those messages.
 */
class SecurityStartupValidationTest {

	private static final String GOOD_SECRET = Base64.getEncoder().encodeToString(new byte[32]);
	private static final String GOOD_PASSWORD = "a-long-enough-password";

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ JwtProperties.class, AdminProperties.class, CorsProperties.class })
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesOnly.class)
			.withPropertyValues(
					"app.jwt.secret=" + GOOD_SECRET,
					"app.admin.email=admin@example.com",
					"app.admin.password=" + GOOD_PASSWORD);

	@Test
	@DisplayName("starts with valid settings")
	void startsWithValidSettings() {
		runner.run(context -> assertThat(context).hasNotFailed());
	}

	// ---- JWT_SECRET ----

	@ParameterizedTest(name = "JWT_SECRET=\"{0}\" is rejected as missing")
	@ValueSource(strings = { "", "   " })
	void rejectsMissingSecret(String secret) {
		runner.withPropertyValues("app.jwt.secret=" + secret).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.isInstanceOf(InvalidSecurityConfigurationException.class)
				.hasMessageContaining("JWT_SECRET is not set"));
	}

	@Test
	@DisplayName("an unset JWT_SECRET (no property at all) is rejected")
	void rejectsAbsentSecret() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
				.withPropertyValues("app.admin.email=admin@example.com", "app.admin.password=" + GOOD_PASSWORD)
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("JWT_SECRET is not set"));
	}

	@Test
	@DisplayName("a JWT_SECRET that decodes to 31 bytes is rejected; 32 bytes is accepted")
	void rejectsShortSecretAcceptsExactMinimum() {
		String thirtyOne = Base64.getEncoder().encodeToString(new byte[31]);
		runner.withPropertyValues("app.jwt.secret=" + thirtyOne).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.hasMessageContaining("JWT_SECRET")
				.hasMessageContaining("31 bytes")
				.hasMessageContaining("at least 32"));

		String thirtyTwo = Base64.getEncoder().encodeToString(new byte[32]);
		runner.withPropertyValues("app.jwt.secret=" + thirtyTwo).run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("a JWT_SECRET that is a long plain-text string but not Base64 is rejected")
	void rejectsNonBase64Secret() {
		runner.withPropertyValues("app.jwt.secret=this is definitely not base64 !!!").run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.hasMessageContaining("JWT_SECRET")
				.hasMessageContaining("Base64"));
	}

	@Test
	@DisplayName("the error message never contains the secret itself")
	void secretNeverLeaksInMessage() {
		String weak = Base64.getEncoder().encodeToString("short-secret".getBytes());
		runner.withPropertyValues("app.jwt.secret=" + weak).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.hasMessageNotContaining(weak)
				.hasMessageNotContaining("short-secret"));
	}

	@ParameterizedTest(name = "JWT_TTL_MINUTES={0} is rejected")
	@ValueSource(strings = { "0", "-5", "1441" })
	void rejectsBadTtl(String ttl) {
		runner.withPropertyValues("app.jwt.ttl-minutes=" + ttl).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause().hasMessageContaining("JWT_TTL_MINUTES"));
	}

	// ---- ADMIN_PASSWORD / ADMIN_EMAIL ----

	@Test
	@DisplayName("an ADMIN_PASSWORD of 11 characters is rejected; 12 is accepted")
	void rejectsShortAdminPassword() {
		String eleven = "a".repeat(11);
		runner.withPropertyValues("app.admin.password=" + eleven).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.isInstanceOf(InvalidSecurityConfigurationException.class)
				.hasMessageContaining("ADMIN_PASSWORD")
				.hasMessageContaining("at least 12")
				.hasMessageNotContaining(eleven));

		runner.withPropertyValues("app.admin.password=" + "a".repeat(12)).run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("a missing ADMIN_PASSWORD is rejected")
	void rejectsMissingAdminPassword() {
		runner.withPropertyValues("app.admin.password=").run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause().hasMessageContaining("ADMIN_PASSWORD"));
	}

	@Test
	@DisplayName("an ADMIN_PASSWORD over BCrypt's 72-byte limit is rejected instead of being silently truncated")
	void rejectsOverlongAdminPassword() {
		runner.withPropertyValues("app.admin.password=" + "a".repeat(73)).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause()
				.hasMessageContaining("ADMIN_PASSWORD")
				.hasMessageContaining("72"));
	}

	@ParameterizedTest(name = "ADMIN_EMAIL=\"{0}\" is rejected")
	@ValueSource(strings = { "", "not-an-email", "a@b" })
	void rejectsBadAdminEmail(String email) {
		runner.withPropertyValues("app.admin.email=" + email).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause().hasMessageContaining("ADMIN_EMAIL"));
	}

	// ---- CORS_ALLOWED_ORIGINS ----

	@ParameterizedTest(name = "CORS origin \"{0}\" is rejected")
	@ValueSource(strings = { "*", "https://*.example.com", "http://localhost:5173/app", "localhost:5173", "ftp://example.com" })
	void rejectsWildcardsAndMalformedOrigins(String origin) {
		runner.withPropertyValues("app.cors.allowed-origins=" + origin).run(context -> assertThat(context)
				.hasFailed().getFailure().rootCause().hasMessageContaining("CORS_ALLOWED_ORIGINS"));
	}

	@Test
	@DisplayName("a comma separated list of exact origins is accepted; empty means no cross-origin access")
	void acceptsExactOrigins() {
		runner.withPropertyValues("app.cors.allowed-origins=http://localhost:5173, https://status.example.com")
				.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
						.containsExactly("http://localhost:5173", "https://status.example.com"));

		runner.withPropertyValues("app.cors.allowed-origins=")
				.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
	}

	// ---- no accidental leaks, readable failure ----

	@Test
	@DisplayName("toString of the properties redacts the secret and the password")
	void toStringRedactsSecrets() {
		runner.run(context -> {
			assertThat(context.getBean(JwtProperties.class).toString()).doesNotContain(GOOD_SECRET);
			assertThat(context.getBean(AdminProperties.class).toString()).doesNotContain(GOOD_PASSWORD);
		});
	}

	@Test
	@DisplayName("the failure analyzer turns the exception into a readable description and action")
	void failureAnalyzerExplainsWhatToDo() {
		InvalidSecurityConfigurationException exception = new InvalidSecurityConfigurationException(
				"JWT_SECRET is not set.", "Set JWT_SECRET in your environment or .env file.");

		FailureAnalysis analysis = new InvalidSecurityConfigurationFailureAnalyzer()
				.analyze(new IllegalStateException("wrapper", exception));

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).contains("JWT_SECRET is not set.");
		assertThat(analysis.getAction()).contains("Set JWT_SECRET");
	}

	@Test
	@DisplayName("the failure analyzer ignores unrelated exceptions")
	void failureAnalyzerIgnoresOthers() {
		assertThat(new InvalidSecurityConfigurationFailureAnalyzer().analyze(new IllegalStateException("other"))).isNull();
	}
}
