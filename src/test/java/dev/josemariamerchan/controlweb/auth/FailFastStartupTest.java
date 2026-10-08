package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.config.InvalidConfigurationException;
import dev.josemariamerchan.controlweb.config.InvalidSecurityConfigurationException;

/**
 * Starts a REAL SpringApplication (real application.yml, real post-processors and failure analyzers)
 * around an empty configuration with no database. If the security settings were only validated when
 * their beans are created, these applications would start fine; they must not.
 */
@ExtendWith(OutputCaptureExtension.class)
class FailFastStartupTest {

	@Configuration(proxyBeanMethods = false)
	static class Empty {
	}

	private static final String SECRET_32_BYTES = Base64.getEncoder().encodeToString(new byte[32]);

	/**
	 * Settings go in as command-line arguments (the highest priority), because application.yml defines
	 * them as empty placeholders that would override any lower-priority default.
	 */
	private static ConfigurableApplicationContext start(String... settings) {
		String[] args = Arrays.stream(settings).map(setting -> "--" + setting).toArray(String[]::new);
		return new SpringApplicationBuilder(Empty.class)
				.web(WebApplicationType.NONE)
				.properties("test.secrets=false") // do not inject the suite's random credentials
				.run(args);
	}

	@Test
	@DisplayName("without JWT_SECRET the application does not start and says what to do")
	void missingJwtSecret(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.admin.email=admin@example.com", "app.admin.password=long-enough-password"))
				.hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class);

		assertThat(output.getAll())
				.contains("APPLICATION FAILED TO START")
				.contains("JWT_SECRET is not set.")
				.contains("Action:")
				.contains("RandomNumberGenerator");
	}

	@Test
	@DisplayName("a JWT_SECRET shorter than 32 bytes prevents startup")
	void shortJwtSecret(CapturedOutput output) {
		String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> start("app.jwt.secret=" + sixteenBytes, "app.admin.email=admin@example.com",
				"app.admin.password=long-enough-password"))
				.hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class);

		assertThat(output.getAll())
				.contains("JWT_SECRET decodes to 16 bytes, but at least 32 are required.")
				.doesNotContain(sixteenBytes);
	}

	@Test
	@DisplayName("an ADMIN_PASSWORD shorter than 12 characters prevents startup, without echoing it")
	void shortAdminPassword(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.jwt.secret=" + SECRET_32_BYTES, "app.admin.email=admin@example.com",
				"app.admin.password=too-short-1"))
				.hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class);

		assertThat(output.getAll())
				.contains("ADMIN_PASSWORD is too short")
				.doesNotContain("too-short-1");
	}

	@Test
	@DisplayName("RETENTION_DAYS below 30 prevents startup with a clear message")
	void retentionBelowThirtyDays(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.jwt.secret=" + SECRET_32_BYTES, "app.admin.email=admin@example.com",
				"app.admin.password=long-enough-password", "app.retention.days=10"))
				.hasRootCauseInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll())
				.contains("APPLICATION FAILED TO START")
				.contains("RETENTION_DAYS is 10, but at least 30 days are required")
				.contains("Action:");
	}

	@Test
	@DisplayName("an invalid HEARTBEAT_URL prevents startup and its (secret) value is never echoed")
	void invalidHeartbeatUrl(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.jwt.secret=" + SECRET_32_BYTES, "app.admin.email=admin@example.com",
				"app.admin.password=long-enough-password", "app.heartbeat.url=http://remote.example.com/SECRET-ping-id-123"))
				.hasRootCauseInstanceOf(InvalidConfigurationException.class);

		assertThat(output.getAll())
				.contains("APPLICATION FAILED TO START")
				.contains("HEARTBEAT_URL is not valid")
				.doesNotContain("SECRET-ping-id-123").doesNotContain("remote.example.com");
	}

	@Test
	@DisplayName("a wildcard in CORS_ALLOWED_ORIGINS prevents startup")
	void wildcardCors(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.jwt.secret=" + SECRET_32_BYTES, "app.admin.email=admin@example.com",
				"app.admin.password=long-enough-password", "app.cors.allowed-origins=*"))
				.hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class);

		assertThat(output.getAll()).contains("CORS_ALLOWED_ORIGINS");
	}

	@Test
	@DisplayName("a wildcard in PUBLIC_CORS_ALLOWED_ORIGINS prevents startup too, naming that variable")
	void wildcardPublicCors(CapturedOutput output) {
		assertThatThrownBy(() -> start("app.jwt.secret=" + SECRET_32_BYTES, "app.admin.email=admin@example.com",
				"app.admin.password=long-enough-password", "app.public.cors-allowed-origins=*"))
				.hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class);

		assertThat(output.getAll()).contains("PUBLIC_CORS_ALLOWED_ORIGINS");
	}

	@Test
	@DisplayName("control: with valid settings the very same application starts")
	void validSettingsStart() {
		try (ConfigurableApplicationContext context = start("app.jwt.secret=" + SECRET_32_BYTES,
				"app.admin.email=admin@example.com", "app.admin.password=long-enough-password")) {
			assertThat(context.isActive()).isTrue();
		}
	}
}
