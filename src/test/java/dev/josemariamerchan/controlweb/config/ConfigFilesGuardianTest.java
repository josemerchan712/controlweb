package dev.josemariamerchan.controlweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Guardian: the YAML files that ship with the application must not contain anything that looks like a literal secret.
 * Every secret reaches the application through the environment ({@code ${NAME:}} with an EMPTY default). The detector
 * itself is tested too, so this guardian cannot silently stop guarding.
 */
class ConfigFilesGuardianTest {

	private static final Pattern TELEGRAM_TOKEN = Pattern.compile("\\d{8,10}:[A-Za-z0-9_-]{30,}");
	private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
	private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE|OPENSSH)");
	private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@");
	private static final Pattern OPAQUE_LONG = Pattern.compile("[A-Za-z0-9+/=_-]{32,}");
	private static final Pattern SECRET_KEY = Pattern.compile(
			"(?i).*(password|passwd|secret|token|api[-_.]?key|private[-_.]?key|credential|chat-id|heartbeat\\.url).*");
	/** `${SOME_VARIABLE:}` -- a reference to the environment with NO default value. */
	private static final Pattern EMPTY_ENV_REFERENCE = Pattern.compile("\\$\\{[A-Za-z0-9_.]+:?}");
	private static final Pattern ANY_ENV_REFERENCE = Pattern.compile("\\$\\{[A-Za-z0-9_.]+(:[^}]*)?}");
	/** Keys that look secret but only hold a number or a duration (jwt.ttl-minutes, retry-after...). */
	private static final Pattern HARMLESS_VALUE = Pattern.compile("(?i)\\d+(ms|s|m|h|d)?|true|false");

	private static Map<String, String> load(String resource) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource(resource));
		Properties properties = yaml.getObject();
		Map<String, String> flat = new TreeMap<>();
		properties.forEach((key, value) -> flat.put(String.valueOf(key), String.valueOf(value)));
		return flat;
	}

	/** Why this key/value pair looks like a literal secret, or null if it does not. */
	static String suspicion(String key, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (TELEGRAM_TOKEN.matcher(value).find()) {
			return "looks like a Telegram bot token";
		}
		if (JWT.matcher(value).find()) {
			return "looks like a JWT";
		}
		if (PEM.matcher(value).find()) {
			return "contains PEM key material";
		}
		if (URL_WITH_CREDENTIALS.matcher(value).find()) {
			return "is a URL with embedded credentials";
		}
		if (OPAQUE_LONG.matcher(value).matches() && !ANY_ENV_REFERENCE.matcher(value).matches()) {
			return "is a long opaque string";
		}
		if (SECRET_KEY.matcher(key).matches() && !EMPTY_ENV_REFERENCE.matcher(value).matches()
				&& !HARMLESS_VALUE.matcher(value).matches() && !key.endsWith("-minutes")) {
			return "is a secret-looking setting whose value is not an environment reference without a default";
		}
		return null;
	}

	private static List<String> findings(Map<String, String> properties) {
		List<String> found = new ArrayList<>();
		properties.forEach((key, value) -> {
			String reason = suspicion(key, value);
			if (reason != null) {
				found.add(key + " " + reason); // the value itself is never printed
			}
		});
		return found;
	}

	@ParameterizedTest
	@ValueSource(strings = { "application.yml", "application-prod.yml", "application-dev.yml" })
	@DisplayName("shipped configuration files contain no literal secret: secrets are environment references with an empty default")
	void shippedFilesHaveNoSecrets(String file) {
		assertThat(findings(load(file))).isEmpty();
	}

	@Test
	@DisplayName("application-prod.yml activates no other profile and imports no other file")
	void prodFileDoesNotActivateOrImport() {
		Map<String, String> prod = load("application-prod.yml");

		assertThat(prod.keySet()).noneMatch(key -> key.startsWith("spring.profiles") || key.startsWith("spring.config"));
	}

	@Test
	@DisplayName("application-prod.yml sets nothing for the secrets the environment must provide")
	void prodFileDoesNotSetTheRequiredSecrets() {
		Map<String, String> prod = load("application-prod.yml");

		assertThat(prod.keySet()).doesNotContain("app.jwt.secret", "app.admin.password", "spring.datasource.password",
				"app.telegram.bot-token", "app.heartbeat.url", "app.proxy.trusted-addresses");
	}

	@Test
	@DisplayName(".env.example holds only empty values or placeholders for anything secret")
	void exampleFileHasOnlyPlaceholders() throws IOException {
		for (String line : Files.readAllLines(Path.of(".env.example"))) {
			if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
				continue;
			}
			String key = line.substring(0, line.indexOf('='));
			String value = line.substring(line.indexOf('=') + 1).strip();
			if (SECRET_KEY.matcher(key).matches() && !HARMLESS_VALUE.matcher(value).matches()) {
				assertThat(value.isEmpty() || ProductionSettingsValidator.isPlaceholder(value))
						.as("%s in .env.example must be empty or an obvious placeholder", key).isTrue();
			}
		}
	}

	// ---- the detector itself ----

	@ParameterizedTest
	@ValueSource(strings = {
			"app.jwt.secret=ZGVmaW5pdGVseS1hLXJlYWwtc2VjcmV0LWtleS1ub3QtZm9yLXJlYWw=",
			"spring.datasource.password=hunter22",
			"app.admin.password=correct-horse-battery",
			"app.telegram.bot-token=123456789:AAFabcdefghijklmnopqrstuvwxyz0123456",
			"app.telegram.chat-id=-1001234567890",
			"app.heartbeat.url=https://hc-ping.com/aaaa-bbbb",
			"some.other.value=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0",
			"some.other.value=-----BEGIN PRIVATE KEY-----",
			"some.other.value=postgresql://user:pw@db.example.com/x",
			"some.other.value=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
			"app.jwt.secret=${JWT_SECRET:default-secret-value}" })
	@DisplayName("the detector catches planted secrets of every kind (so the guardian cannot be vacuous)")
	void detectorCatchesPlantedSecrets(String planted) {
		String key = planted.substring(0, planted.indexOf('='));
		String value = planted.substring(planted.indexOf('=') + 1);

		assertThat(suspicion(key, value)).as(planted).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "app.jwt.secret=${JWT_SECRET:}", "app.admin.password=${ADMIN_PASSWORD:}",
			"app.telegram.bot-token=${TELEGRAM_BOT_TOKEN:}", "app.heartbeat.url=${HEARTBEAT_URL:}", "app.jwt.ttl-minutes=60",
			"app.telegram.max-retry-after=10s", "management.endpoints.web.exposure.include=health",
			"app.retention.cron=0 45 4 * * *", "spring.flyway.ignore-migration-patterns=*:future",
			"logging.level.org.hibernate.SQL=WARN", "app.checker.user-agent=ControlWeb/1.0",
			"app.retention.days=${RETENTION_DAYS:90}" })
	@DisplayName("the detector accepts the normal settings of this project")
	void detectorAcceptsNormalSettings(String normal) {
		String key = normal.substring(0, normal.indexOf('='));
		String value = normal.substring(normal.indexOf('=') + 1);

		assertThat(suspicion(key, value)).as(normal).isNull();
	}
}
