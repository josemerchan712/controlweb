package dev.josemariamerchan.controlweb.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.config.InvalidConfigurationException;
import dev.josemariamerchan.controlweb.config.LoginRateLimitProperties;
import dev.josemariamerchan.controlweb.config.ProxyProperties;

/** The application refuses to start with a trusted-proxy list that could be abused, or limits that make no sense. */
class RateLimitPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ ProxyProperties.class, LoginRateLimitProperties.class })
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

	@Test
	@DisplayName("defaults: no trusted proxy, and the agreed limits")
	void defaults() {
		runner.run(context -> {
			assertThat(context.getBean(ProxyProperties.class).trustedAddresses()).isEmpty();
			LoginRateLimitProperties limits = context.getBean(LoginRateLimitProperties.class);
			assertThat(limits.ipAttempts()).isEqualTo(10);
			assertThat(limits.ipAttemptsRefill()).isEqualTo(Duration.ofSeconds(6));
			assertThat(limits.ipFailures()).isEqualTo(5);
			assertThat(limits.ipFailuresRefill()).isEqualTo(Duration.ofMinutes(3));
			assertThat(limits.accountFailures()).isEqualTo(20);
			assertThat(limits.accountFailuresRefill()).isEqualTo(Duration.ofMinutes(3));
			assertThat(limits.globalAttempts()).isEqualTo(60);
			assertThat(limits.globalAttemptsRefill()).isEqualTo(Duration.ofSeconds(1));
			assertThat(limits.maxKeys()).isEqualTo(10_000);
		});
	}

	@Test
	@DisplayName("TRUSTED_PROXY_ADDRESSES accepts exact IPv4 and IPv6 addresses, comma separated")
	void exactAddressesAreAccepted() {
		runner.withPropertyValues("app.proxy.trusted-addresses=10.77.0.10, 2001:db8::1").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ProxyProperties.class).trustedAddresses()).containsExactly("10.77.0.10", "2001:db8::1");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.77.0.0/24", "10.77.0.*", "10.77", "caddy", "example.com", "0.0.0.0", "::", "256.1.1.1",
			"10.77.0.10:80", "*" })
	@DisplayName("ranges, names, wildcards and 'any' addresses are refused: trust is a single exact address")
	void rangesAndNamesAreRefused(String value) {
		assertThatThrownBy(() -> new ProxyProperties(List.of(value))).isInstanceOf(InvalidConfigurationException.class)
				.hasMessageContaining("TRUSTED_PROXY_ADDRESSES");
		runner.withPropertyValues("app.proxy.trusted-addresses=" + value).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = { "app.login-rate-limit.ip-attempts=0", "app.login-rate-limit.ip-failures=0",
			"app.login-rate-limit.account-failures=0", "app.login-rate-limit.global-attempts=0",
			"app.login-rate-limit.ip-failures-refill=0s", "app.login-rate-limit.account-failures-refill=1d",
			"app.login-rate-limit.max-keys=5", "app.login-rate-limit.ip-attempts=-3" })
	@DisplayName("limits that are zero, negative or absurd stop the application from starting")
	void absurdLimitsAreRefused(String setting) {
		runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
	}
}
