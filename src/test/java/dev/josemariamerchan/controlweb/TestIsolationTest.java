package dev.josemariamerchan.controlweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The test suite must never see the developer's real {@code .env}: no real secrets, no real Telegram chat. */
class TestIsolationTest {

	/** The name Spring Boot gives to the source loaded through {@code spring.config.import=optional:file:.env[.properties]}. */
	private static final String DOT_ENV_SOURCE = "Config resource 'file [.env]' via location 'optional:file:.env[.properties]'";

	private final TestSecretsEnvironmentPostProcessor postProcessor = new TestSecretsEnvironmentPostProcessor();

	private MockEnvironment environmentWithRealDotEnv() {
		MockEnvironment environment = new MockEnvironment();
		environment.getPropertySources().addLast(new MapPropertySource("Config resource 'class path resource [application.yml]'",
				Map.of("app.checker.connect-timeout", "5s", "app.jwt.secret", "")));
		environment.getPropertySources().addFirst(new MapPropertySource(DOT_ENV_SOURCE, Map.of(
				"JWT_SECRET", "real-jwt-secret-from-dot-env",
				"app.jwt.secret", "real-jwt-secret-from-dot-env",
				"app.telegram.bot-token", "999999999:REALTOKENFROMDOTENVxxxxxxxxxxxxxxxxxx",
				"app.telegram.chat-id", "424242")));
		return environment;
	}

	@Test
	@DisplayName("values from the developer's .env are dropped: real secrets and the real Telegram chat are invisible to tests")
	void dotEnvIsRemoved() {
		MockEnvironment environment = environmentWithRealDotEnv();

		postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getPropertySources().contains(DOT_ENV_SOURCE)).isFalse();
		assertThat(environment.getProperty("app.telegram.bot-token")).isNull();
		assertThat(environment.getProperty("app.telegram.chat-id")).isNull();
		assertThat(environment.getProperty("app.jwt.secret")).isEqualTo(TestCredentials.JWT_SECRET);
		assertThat(environment.getProperty("app.jwt.secret")).doesNotContain("real-jwt-secret");
	}

	@Test
	@DisplayName("with test.secrets=false the .env is STILL ignored, so an app without secrets really has none")
	void dotEnvIsIgnoredEvenWithoutTestSecrets() {
		MockEnvironment environment = environmentWithRealDotEnv();
		environment.setProperty("test.secrets", "false");

		postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("app.jwt.secret")).as("only the empty value from application.yml remains").isEmpty();
		assertThat(environment.getProperty("app.telegram.bot-token")).isNull();
	}

	@Test
	@DisplayName("everything that is not the .env file is left alone")
	void otherSourcesAreKept() {
		MockEnvironment environment = environmentWithRealDotEnv();

		postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("app.checker.connect-timeout")).isEqualTo("5s");
	}

	@Test
	@DisplayName("without a .env file nothing breaks")
	void noDotEnvFile() {
		MockEnvironment environment = new MockEnvironment();

		postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("app.jwt.secret")).isEqualTo(TestCredentials.JWT_SECRET);
	}
}
