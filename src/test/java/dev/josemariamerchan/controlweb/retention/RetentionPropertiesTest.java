package dev.josemariamerchan.controlweb.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.config.InvalidConfigurationFailureAnalyzer;
import dev.josemariamerchan.controlweb.config.RetentionProperties;

/** Retention below 30 days is refused at startup, with a message that says what to change. */
class RetentionPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RetentionProperties.class)
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

	@Test
	@DisplayName("defaults: enabled, 90 days, batches of 5000, early morning")
	void defaults() {
		runner.run(context -> {
			RetentionProperties properties = context.getBean(RetentionProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.days()).isEqualTo(90);
			assertThat(properties.batchSize()).isEqualTo(5000);
			assertThat(properties.cron()).isEqualTo("0 30 3 * * *");
		});
	}

	@Test
	@DisplayName("30 days (the minimum) is accepted")
	void thirtyIsAccepted() {
		runner.withPropertyValues("app.retention.days=30").run(context -> assertThat(context).hasNotFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = { "29", "7", "1", "0", "-5" })
	@DisplayName("fewer than 30 days fails startup with a clear message")
	void lessThanThirtyFails(String days) {
		runner.withPropertyValues("app.retention.days=" + days).run(context -> {
			assertThat(context).hasFailed();
			FailureAnalysis analysis = new InvalidConfigurationFailureAnalyzer().analyze(context.getStartupFailure());
			assertThat(analysis).isNotNull();
			assertThat(analysis.getDescription()).contains("RETENTION_DAYS").contains("30");
			assertThat(analysis.getAction()).contains("RETENTION_DAYS");
		});
	}

	@Test
	@DisplayName("a batch size outside 100..50000 fails startup")
	void invalidBatchSizeFails() {
		runner.withPropertyValues("app.retention.batch-size=0").run(context -> assertThat(context).hasFailed());
	}
}
