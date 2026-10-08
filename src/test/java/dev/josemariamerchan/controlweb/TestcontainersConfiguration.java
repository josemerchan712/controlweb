package dev.josemariamerchan.controlweb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a PostgreSQL 16 container for integration tests. There is ONE container per test JVM,
 * shared by every Spring context (the dev laptop only has 8 GB of RAM). Testcontainers' Ryuk
 * sidecar removes it when the JVM ends, so Spring must not stop it ({@code destroyMethod = ""}).
 *
 * <p>Because the database is shared, tests that commit data must clean up after themselves.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	static {
		POSTGRES.start();
	}

	@Bean(destroyMethod = "")
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

	/** Alerts never leave the machine in tests: this primary notifier records them instead. */
	@Bean
	@org.springframework.context.annotation.Primary
	RecordingNotifier recordingNotifier() {
		return new RecordingNotifier();
	}

	/** Present in every test context that imports this configuration, so no test needs a different context to see events. */
	@Bean
	IncidentEventRecorder incidentEventRecorder() {
		return new IncidentEventRecorder();
	}

}
