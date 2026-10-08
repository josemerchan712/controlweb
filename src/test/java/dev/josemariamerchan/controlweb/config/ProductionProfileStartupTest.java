package dev.josemariamerchan.controlweb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.RestoreLoggingLevels;

/**
 * Starts a REAL SpringApplication (real application.yml, real application-prod.yml, real post-processors and failure
 * analyzers) around an empty configuration, to prove the production checks are wired in and that the files of one
 * profile cannot leak into another. No database is needed: the checks run before any bean exists.
 */
@ExtendWith({ OutputCaptureExtension.class, RestoreLoggingLevels.class })
class ProductionProfileStartupTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Configuration(proxyBeanMethods = false)
	static class Empty {
	}

	private static String randomBase64(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return Base64.getEncoder().encodeToString(value);
	}

	/** Settings that are valid in production, as command-line arguments (the highest priority). */
	private static List<String> validSettings() {
		return new ArrayList<>(List.of(
				"app.jwt.secret=" + randomBase64(32),
				"app.admin.email=admin@example.com",
				"app.admin.password=" + randomBase64(18),
				"spring.datasource.password=" + randomBase64(18),
				"app.proxy.trusted-addresses=10.77.0.10",
				"app.public.cors-allowed-origins=https://josemariamerchan.dev"));
	}

	private static ConfigurableApplicationContext start(String profiles, List<String> settings) {
		List<String> arguments = new ArrayList<>(settings);
		arguments.add("spring.profiles.active=" + profiles);
		String[] args = arguments.stream().map(setting -> "--" + setting).toArray(String[]::new);
		return new SpringApplicationBuilder(Empty.class)
				.web(WebApplicationType.NONE)
				.properties("test.secrets=false") // do not inject the suite's random credentials
				.run(args);
	}

	@Test
	@DisplayName("prod with valid settings starts, and application-prod.yml really is the profile in force")
	void validProductionStarts() {
		try (ConfigurableApplicationContext context = start("prod", validSettings())) {
			assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
			assertThat(context.getEnvironment().getProperty("app.checker.allow-private-hosts", Boolean.class)).isFalse();
			assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
			assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
			assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
			assertThat(context.getEnvironment().getProperty("app.scheduler.enabled", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty("app.retention.days", Integer.class)).isEqualTo(90);
			assertThat(context.getEnvironment().getProperty("app.retention.cron")).isEqualTo("0 45 4 * * *");
		}
	}

	@Test
	@DisplayName("prod without TRUSTED_PROXY_ADDRESSES does not start and says so, without printing any secret")
	void missingTrustedProxies(CapturedOutput output) {
		List<String> settings = validSettings();
		settings.removeIf(setting -> setting.startsWith("app.proxy.trusted-addresses"));
		String jwtSecret = settings.get(0).substring("app.jwt.secret=".length());

		assertThatThrownBy(() -> start("prod", settings)).isInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll())
				.contains("APPLICATION FAILED TO START")
				.contains("TRUSTED_PROXY_ADDRESSES is empty")
				.contains("Action:")
				.doesNotContain(jwtSecret);
	}

	@Test
	@DisplayName("the test profile cannot sneak into production: prod+test is refused (loose rate limits, private hosts, localhost CORS)")
	void testProfileCannotReachProduction(CapturedOutput output) {
		assertThatThrownBy(() -> start("prod,test", validSettings())).isInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll())
				.contains("APPLICATION FAILED TO START")
				.contains("login rate limit")
				.contains("allow-private-hosts")
				.contains("CORS_ALLOWED_ORIGINS");
	}

	@Test
	@DisplayName("the dev profile cannot sneak into production either: prod+dev is refused")
	void devProfileCannotReachProduction(CapturedOutput output) {
		assertThatThrownBy(() -> start("prod,dev", validSettings())).isInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll()).contains("allow-private-hosts").contains("API documentation");
	}

	@Test
	@DisplayName("the example values of .env.example are refused with the real files too")
	void exampleValuesAreRefused(CapturedOutput output) {
		List<String> settings = validSettings();
		settings.removeIf(setting -> setting.startsWith("spring.datasource.password"));
		settings.add("spring.datasource.password=change-me");

		assertThatThrownBy(() -> start("prod", settings)).isInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll()).contains("DB_PASSWORD is an example value").doesNotContain("password=change-me");
	}

	@Test
	@DisplayName("the same loose settings are fine outside production (the checks only guard the prod profile)")
	void looseSettingsAreFineWithoutProd() {
		List<String> settings = new ArrayList<>(Arrays.asList(
				"app.jwt.secret=" + randomBase64(32), "app.admin.email=admin@example.com",
				"app.admin.password=" + randomBase64(18), "app.checker.allow-private-hosts=true",
				"app.cors.allowed-origins=http://localhost:5173"));

		try (ConfigurableApplicationContext context = start("dev", settings)) {
			assertThat(context.isRunning()).isTrue();
		}
	}
}
