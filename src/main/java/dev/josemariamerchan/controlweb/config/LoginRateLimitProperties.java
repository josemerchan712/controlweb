package dev.josemariamerchan.controlweb.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of {@code POST /api/auth/login}. Each limit is a token bucket: it holds up to {@code capacity} tokens and gets
 * one back every {@code refill}. There is no switch to turn the limiter off.
 *
 * @param ipAttempts             burst of attempts (any result, even malformed) one client may make
 * @param ipAttemptsRefill       time per attempt token: 6 s = 10 attempts per minute sustained
 * @param ipFailures             burst of FAILED logins one client may make
 * @param ipFailuresRefill       time per failure token: 3 min = 20 failures per hour sustained
 * @param accountFailures        burst of failed logins one ACCOUNT (email, existing or not) may suffer from all IPs together
 * @param accountFailuresRefill  time per failure token of an account
 * @param globalAttempts         burst of attempts the whole server accepts (so BCrypt cannot be used to exhaust the CPU)
 * @param globalAttemptsRefill   time per global token: 1 s = 60 attempts per minute sustained
 * @param maxKeys                most clients (and most accounts) remembered; the least recently used are forgotten beyond it
 */
@ConfigurationProperties("app.login-rate-limit")
public record LoginRateLimitProperties(
		@DefaultValue("10") int ipAttempts,
		@DefaultValue("6s") Duration ipAttemptsRefill,
		@DefaultValue("5") int ipFailures,
		@DefaultValue("3m") Duration ipFailuresRefill,
		@DefaultValue("20") int accountFailures,
		@DefaultValue("3m") Duration accountFailuresRefill,
		@DefaultValue("60") int globalAttempts,
		@DefaultValue("1s") Duration globalAttemptsRefill,
		@DefaultValue("10000") int maxKeys) {

	private static final int MAX_CAPACITY = 100_000;
	private static final Duration MIN_REFILL = Duration.ofMillis(1);
	private static final Duration MAX_REFILL = Duration.ofHours(12);

	public LoginRateLimitProperties {
		capacity(ipAttempts, "ip-attempts");
		refill(ipAttemptsRefill, "ip-attempts-refill");
		capacity(ipFailures, "ip-failures");
		refill(ipFailuresRefill, "ip-failures-refill");
		capacity(accountFailures, "account-failures");
		refill(accountFailuresRefill, "account-failures-refill");
		capacity(globalAttempts, "global-attempts");
		refill(globalAttemptsRefill, "global-attempts-refill");
		if (maxKeys < 100 || maxKeys > 1_000_000) {
			throw invalid("max-keys", "a number between 100 and 1000000");
		}
	}

	private static void capacity(int value, String name) {
		if (value < 1 || value > MAX_CAPACITY) {
			throw invalid(name, "a number between 1 and " + MAX_CAPACITY);
		}
	}

	private static void refill(Duration value, String name) {
		if (value == null || value.compareTo(MIN_REFILL) < 0 || value.compareTo(MAX_REFILL) > 0) {
			throw invalid(name, "a duration between 1ms and 12h");
		}
	}

	private static InvalidSecurityConfigurationException invalid(String name, String expected) {
		return new InvalidSecurityConfigurationException("app.login-rate-limit." + name + " is not valid.",
				"Set app.login-rate-limit." + name + " to " + expected + ".");
	}
}
