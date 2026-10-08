package dev.josemariamerchan.controlweb.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.config.HeartbeatProperties;
import dev.josemariamerchan.controlweb.config.InvalidConfigurationException;

/** The heartbeat URL is a secret (whoever knows it can fake "I am alive"): validated at startup, never echoed. */
class HeartbeatPropertiesTest {

	private static final String SECRET = "SECRET-ping-id-123";

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(HeartbeatProperties.class)
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

	@Test
	@DisplayName("by default the heartbeat is off: no URL, one ping per minute at most, 5 s timeout")
	void defaults() {
		runner.run(context -> {
			HeartbeatProperties properties = context.getBean(HeartbeatProperties.class);
			assertThat(properties.enabled()).isFalse();
			assertThat(properties.minInterval()).isEqualTo(Duration.ofSeconds(60));
			assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));
		});
	}

	@Test
	@DisplayName("a blank URL (an empty HEARTBEAT_URL) keeps it off")
	void blankIsOff() {
		runner.withPropertyValues("app.heartbeat.url=   ").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(HeartbeatProperties.class).enabled()).isFalse();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://hc-ping.com/" + SECRET, "https://hc-ping.com/" + SECRET + "/fail",
			"http://localhost:8099/" + SECRET, "http://127.0.0.1/" + SECRET, "http://[::1]:9/" + SECRET })
	@DisplayName("https URLs are accepted, and plain http only to the local machine")
	void validUrls(String url) {
		runner.withPropertyValues("app.heartbeat.url=" + url).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(HeartbeatProperties.class).enabled()).isTrue();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://hc-ping.com/" + SECRET, "ftp://hc-ping.com/" + SECRET, "hc-ping.com/" + SECRET,
			"https:///" + SECRET, "https://user:pw@hc-ping.com/" + SECRET, "https://hc ping.com/" + SECRET, "not a url " + SECRET,
			"javascript:alert('" + SECRET + "')", "http://localhost.evil.example/" + SECRET })
	@DisplayName("plain http to a remote host, other schemes, missing hosts and embedded credentials are refused")
	void invalidUrls(String url) {
		assertThatThrownBy(() -> new HeartbeatProperties(url, Duration.ofSeconds(60), Duration.ofSeconds(5)))
				.isInstanceOf(InvalidConfigurationException.class).hasMessageContaining("HEARTBEAT_URL")
				.hasMessageNotContaining(SECRET);
		runner.withPropertyValues("app.heartbeat.url=" + url).run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("a URL longer than 2048 characters is refused")
	void tooLong() {
		String url = "https://hc-ping.com/" + "a".repeat(2100);

		assertThatThrownBy(() -> new HeartbeatProperties(url, Duration.ofSeconds(60), Duration.ofSeconds(5)))
				.isInstanceOf(InvalidConfigurationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "app.heartbeat.min-interval=1s", "app.heartbeat.min-interval=2h", "app.heartbeat.timeout=0s",
			"app.heartbeat.timeout=1m" })
	@DisplayName("absurd intervals and timeouts stop the application from starting")
	void absurdTimings(String setting) {
		runner.withPropertyValues("app.heartbeat.url=https://hc-ping.com/x", setting)
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("toString never shows the URL")
	void toStringIsRedacted() {
		HeartbeatProperties properties = new HeartbeatProperties("https://hc-ping.com/" + SECRET, Duration.ofSeconds(60),
				Duration.ofSeconds(5));

		assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain("hc-ping");
	}
}
