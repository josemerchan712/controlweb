package dev.josemariamerchan.controlweb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * The production profile refuses to start with a configuration that is only acceptable on a developer's machine. One
 * test per condition; each error names WHAT is wrong, never the value (several of them are secrets).
 */
class ProductionSettingsValidatorTest {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String GOOD_JWT_SECRET = randomBase64(32);
	private static final String GOOD_ADMIN_PASSWORD = randomBase64(18);
	private static final String GOOD_DB_PASSWORD = randomBase64(18);
	private static final String HEARTBEAT = "https://hc-ping.example.net/SECRET-ping-id-123";

	private final ProductionSettingsValidator validator = new ProductionSettingsValidator();

	private static String randomBase64(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return Base64.getEncoder().encodeToString(value);
	}

	private static Map<String, String> baseline() {
		Map<String, String> properties = new java.util.LinkedHashMap<>();
		properties.put("app.proxy.trusted-addresses", "10.77.0.10");
		properties.put("app.checker.allow-private-hosts", "false");
		properties.put("springdoc.api-docs.enabled", "false");
		properties.put("springdoc.swagger-ui.enabled", "false");
		properties.put("app.cors.allowed-origins", "https://dashboard.example.com");
		properties.put("app.public.cors-allowed-origins", "https://josemariamerchan.dev");
		properties.put("app.jwt.secret", GOOD_JWT_SECRET);
		properties.put("app.admin.password", GOOD_ADMIN_PASSWORD);
		properties.put("spring.datasource.password", GOOD_DB_PASSWORD);
		properties.put("app.heartbeat.url", HEARTBEAT);
		return properties;
	}

	/** A configuration that passes in production. Tests change ONE thing (or call {@link #validWithout}). */
	private static MockEnvironment valid() {
		return validWithout();
	}

	/** The valid configuration with some properties not set at all. */
	private static MockEnvironment validWithout(String... missing) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("prod");
		Map<String, String> properties = baseline();
		List.of(missing).forEach(properties::remove);
		properties.forEach(environment::setProperty);
		return environment;
	}

	private void check(MockEnvironment environment) {
		validator.postProcessEnvironment(environment, new SpringApplication());
	}

	private String failureOf(MockEnvironment environment) {
		Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> check(environment));
		assertThat(failure).as("the production profile should have refused to start").isInstanceOf(InvalidConfigurationException.class);
		InvalidConfigurationException exception = (InvalidConfigurationException) failure;
		return exception.getMessage() + " || " + exception.getAction();
	}

	// ---- the baseline and when the check applies ----

	@Test
	@DisplayName("a valid production configuration starts")
	void validConfigurationPasses() {
		assertThatCode(() -> check(valid())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("an empty CORS_ALLOWED_ORIGINS and no heartbeat are fine (no dashboard yet, heartbeat is optional)")
	void optionalThingsMayBeEmpty() {
		MockEnvironment environment = valid();
		environment.setProperty("app.cors.allowed-origins", "");
		environment.setProperty("app.heartbeat.url", "");

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "staging", "production" })
	@DisplayName("the checks apply to the profile 'prod' only: any other profile may keep developer settings")
	void onlyTheProdProfileIsChecked(String profile) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profile);
		environment.setProperty("app.checker.allow-private-hosts", "true");
		environment.setProperty("app.cors.allowed-origins", "http://localhost:5173");

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("no profile at all (plain local run) is not checked either")
	void noProfileIsNotChecked() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("app.checker.allow-private-hosts", "true");

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("prod combined with another profile is still prod")
	void prodWithAnotherProfile() {
		MockEnvironment environment = valid();
		environment.setActiveProfiles("dev", "prod");
		environment.setProperty("app.checker.allow-private-hosts", "true");

		assertThat(failureOf(environment)).contains("allow-private-hosts");
	}

	// ---- 1. trusted proxies ----

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", " , ," })
	@DisplayName("empty TRUSTED_PROXY_ADDRESSES: every client would share Caddy's address and 5 failures would lock everybody out")
	void emptyTrustedProxies(String value) {
		MockEnvironment environment = valid();
		environment.setProperty("app.proxy.trusted-addresses", value);

		assertThat(failureOf(environment)).contains("TRUSTED_PROXY_ADDRESSES");
	}

	@Test
	@DisplayName("TRUSTED_PROXY_ADDRESSES missing altogether is the same error")
	void missingTrustedProxies() {
		assertThat(failureOf(validWithout("app.proxy.trusted-addresses"))).contains("TRUSTED_PROXY_ADDRESSES");
	}

	// ---- 2. private hosts ----

	@ParameterizedTest
	@ValueSource(strings = { "true", "TRUE" })
	@DisplayName("allow-private-hosts=true would switch off the SSRF guard")
	void allowPrivateHosts(String value) {
		MockEnvironment environment = valid();
		environment.setProperty("app.checker.allow-private-hosts", value);

		assertThat(failureOf(environment)).contains("allow-private-hosts");
	}

	// ---- 3. API documentation ----

	@ParameterizedTest
	@ValueSource(strings = { "springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled" })
	@DisplayName("API documentation enabled (api-docs or Swagger UI)")
	void apiDocsEnabled(String property) {
		MockEnvironment environment = valid();
		environment.setProperty(property, "true");

		assertThat(failureOf(environment)).contains("API documentation");
	}

	@ParameterizedTest
	@ValueSource(strings = { "springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled" })
	@DisplayName("API documentation not explicitly disabled: springdoc is ON by default, so a missing setting is refused")
	void apiDocsNotExplicitlyDisabled(String property) {
		assertThat(failureOf(validWithout(property))).contains("API documentation");
	}

	// ---- 4. login rate limits ----

	static Stream<Arguments> looserLimits() {
		return Stream.of(
				Arguments.of("app.login-rate-limit.ip-attempts", "11"),
				Arguments.of("app.login-rate-limit.ip-attempts-refill", "5s"),
				Arguments.of("app.login-rate-limit.ip-failures", "6"),
				Arguments.of("app.login-rate-limit.ip-failures-refill", "2m"),
				Arguments.of("app.login-rate-limit.account-failures", "21"),
				Arguments.of("app.login-rate-limit.account-failures-refill", "2m"),
				Arguments.of("app.login-rate-limit.global-attempts", "61"),
				Arguments.of("app.login-rate-limit.global-attempts-refill", "900ms"),
				// what application-test.yml sets: it must not be able to reach production
				Arguments.of("app.login-rate-limit.ip-failures", "100000"),
				Arguments.of("app.login-rate-limit.account-failures-refill", "1ms"));
	}

	@ParameterizedTest(name = "{0}={1}")
	@MethodSource("looserLimits")
	@DisplayName("a login rate limit looser than the production one (more tokens, or faster refill) is refused")
	void looserRateLimits(String property, String value) {
		MockEnvironment environment = valid();
		environment.setProperty(property, value);

		assertThat(failureOf(environment)).contains("login rate limit");
	}

	@ParameterizedTest(name = "{0}={1}")
	@MethodSource("stricterLimits")
	@DisplayName("stricter limits than production are allowed")
	void stricterRateLimits(String property, String value) {
		MockEnvironment environment = valid();
		environment.setProperty(property, value);

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	static Stream<Arguments> stricterLimits() {
		return Stream.of(
				Arguments.of("app.login-rate-limit.ip-failures", "3"),
				Arguments.of("app.login-rate-limit.account-failures-refill", "10m"),
				Arguments.of("app.login-rate-limit.global-attempts", "30"),
				Arguments.of("app.login-rate-limit.ip-attempts", "10"),
				Arguments.of("app.login-rate-limit.ip-attempts-refill", "6s"));
	}

	// ---- 5. CORS ----

	@ParameterizedTest
	@ValueSource(strings = { "http://dashboard.example.com", "http://localhost:5173", "https://localhost", "https://localhost:5173",
			"HTTPS://LOCALHOST:8080", "https://127.0.0.1", "https://127.0.0.1:3000", "https://[::1]:3000",
			"https://good.example.com,http://bad.example.com", "https://good.example.com, https://localhost:1" })
	@DisplayName("CORS_ALLOWED_ORIGINS with plain http:// or localhost is refused")
	void privateCorsOrigins(String origins) {
		MockEnvironment environment = valid();
		environment.setProperty("app.cors.allowed-origins", origins);

		String failure = failureOf(environment);

		assertThat(failure).contains("CORS_ALLOWED_ORIGINS").doesNotContain("bad.example.com").doesNotContain("good.example.com");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://josemariamerchan.dev", "https://localhost:4173", "http://127.0.0.1:5173",
			"https://josemariamerchan.dev,http://other.example.com" })
	@DisplayName("PUBLIC_CORS_ALLOWED_ORIGINS with plain http:// or localhost is refused too")
	void publicCorsOrigins(String origins) {
		MockEnvironment environment = valid();
		environment.setProperty("app.public.cors-allowed-origins", origins);

		assertThat(failureOf(environment)).contains("PUBLIC_CORS_ALLOWED_ORIGINS");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://josemariamerchan.dev", "https://app.example.com:8443", "https://localhost.example.com",
			"https://notlocalhost.dev", "https://a.example.com,https://b.example.com" })
	@DisplayName("https origins of real hosts are accepted (a host that merely contains the word localhost is not localhost)")
	void realHttpsOriginsPass(String origins) {
		MockEnvironment environment = valid();
		environment.setProperty("app.cors.allowed-origins", origins);
		environment.setProperty("app.public.cors-allowed-origins", origins);

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	// ---- 6. placeholder secrets ----

	@ParameterizedTest
	@ValueSource(strings = { "change-me", "CHANGE-ME", "  change-me  ", "changeme", "password", "secret", "postgres", "controlweb", "admin" })
	@DisplayName("DB_PASSWORD equal to an example value is refused (and the value is never echoed)")
	void dbPasswordPlaceholder(String value) {
		MockEnvironment environment = valid();
		environment.setProperty("spring.datasource.password", value);

		assertThat(failureOf(environment)).contains("DB_PASSWORD").doesNotContain(value.strip());
	}

	@Test
	@DisplayName("an empty DB_PASSWORD is refused")
	void dbPasswordEmpty() {
		MockEnvironment environment = valid();
		environment.setProperty("spring.datasource.password", "");

		assertThat(failureOf(environment)).contains("DB_PASSWORD");
	}

	@ParameterizedTest
	@ValueSource(strings = { "change-me", "Password", "admin", "changeme", "" })
	@DisplayName("ADMIN_PASSWORD equal to an example value, or empty, is refused")
	void adminPasswordPlaceholder(String value) {
		MockEnvironment environment = valid();
		environment.setProperty("app.admin.password", value);

		assertThat(failureOf(environment)).contains("ADMIN_PASSWORD");
	}

	@ParameterizedTest
	@ValueSource(strings = { "change-me", "secret", "" })
	@DisplayName("JWT_SECRET equal to an example value, or empty, is refused")
	void jwtSecretPlaceholder(String value) {
		MockEnvironment environment = valid();
		environment.setProperty("app.jwt.secret", value);

		assertThat(failureOf(environment)).contains("JWT_SECRET");
	}

	@Test
	@DisplayName("a JWT_SECRET made of one repeated byte (32 zero bytes, as in examples and tests) is refused")
	void jwtSecretOfRepeatedBytes() {
		MockEnvironment environment = valid();
		environment.setProperty("app.jwt.secret", Base64.getEncoder().encodeToString(new byte[32]));

		assertThat(failureOf(environment)).contains("JWT_SECRET");
	}

	@Test
	@DisplayName("every secret in .env.example is recognised as a placeholder, so copying the example file unchanged cannot reach production")
	void everyValueOfTheExampleFileIsRefused() throws IOException {
		List<String> lines = Files.readAllLines(Path.of(".env.example"));
		int checked = 0;
		for (String variable : List.of("DB_PASSWORD", "JWT_SECRET", "ADMIN_PASSWORD")) {
			for (String line : lines) {
				if (line.startsWith(variable + "=")) {
					String value = line.substring(variable.length() + 1).strip();
					checked++;
					assertThat(value.isEmpty() || ProductionSettingsValidator.isPlaceholder(value))
							.as("the example value of %s must be refused in production", variable).isTrue();
				}
			}
		}
		assertThat(checked).as("the three variables are in .env.example").isEqualTo(3);
	}

	// ---- 7. heartbeat ----

	@ParameterizedTest
	@ValueSource(strings = { "http://hc-ping.example.net/SECRET-ping-id-123", "http://localhost:9000/SECRET-ping-id-123",
			"HTTP://hc-ping.example.net/SECRET-ping-id-123", "ftp://hc-ping.example.net/SECRET-ping-id-123",
			"hc-ping.example.net/SECRET-ping-id-123" })
	@DisplayName("a HEARTBEAT_URL that is set but is not https is refused, and the (secret) URL is not echoed")
	void heartbeatMustBeHttps(String url) {
		MockEnvironment environment = valid();
		environment.setProperty("app.heartbeat.url", url);

		assertThat(failureOf(environment)).contains("HEARTBEAT_URL").doesNotContain("SECRET-ping-id-123")
				.doesNotContain("hc-ping.example.net");
	}

	@Test
	@DisplayName("a blank HEARTBEAT_URL means 'off' and is not a violation")
	void blankHeartbeatIsOff() {
		MockEnvironment environment = valid();
		environment.setProperty("app.heartbeat.url", "   ");

		assertThatCode(() -> check(environment)).doesNotThrowAnyException();
	}

	// ---- reporting ----

	@Test
	@DisplayName("all problems are reported at once, so they can be fixed in one go")
	void allProblemsAreReportedTogether() {
		MockEnvironment environment = valid();
		environment.setProperty("app.checker.allow-private-hosts", "true");
		environment.setProperty("app.cors.allowed-origins", "http://localhost:5173");
		environment.setProperty("spring.datasource.password", "change-me");
		environment.setProperty("app.heartbeat.url", "http://hc-ping.example.net/x");

		assertThat(failureOf(environment)).contains("allow-private-hosts", "CORS_ALLOWED_ORIGINS", "DB_PASSWORD", "HEARTBEAT_URL");
	}

	@Test
	@DisplayName("no error message ever contains a secret value of the configuration")
	void messagesNeverEchoValues() {
		MockEnvironment environment = valid();
		environment.setProperty("app.jwt.secret", "change-me-jwt-LEAK-1");
		environment.setProperty("app.admin.password", "change-me");
		environment.setProperty("spring.datasource.password", "change-me");
		environment.setProperty("app.checker.allow-private-hosts", "true");
		environment.setProperty("app.heartbeat.url", "http://SECRET-ping-host.example.net/SECRET-ping-id-123");
		environment.setProperty("app.cors.allowed-origins", "http://LEAK-origin.example.net");

		String failure = failureOf(environment);

		assertThat(failure).doesNotContain("LEAK").doesNotContain("SECRET-ping").doesNotContain("change-me")
				.doesNotContain(GOOD_JWT_SECRET).doesNotContain(GOOD_DB_PASSWORD);
		assertThatThrownBy(() -> check(environment)).hasMessageNotContaining("LEAK");
	}

	@Test
	@DisplayName("the production ceiling for the rate limits is exactly the documented default values")
	void ceilingIsTheDefaultConfiguration() {
		LoginRateLimitProperties bound = org.springframework.boot.context.properties.bind.Binder.get(new MockEnvironment())
				.bindOrCreate("app.login-rate-limit", LoginRateLimitProperties.class);

		assertThat(LoginRateLimitProperties.defaults()).isEqualTo(bound);
	}
}
