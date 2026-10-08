package dev.josemariamerchan.controlweb.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.TestClock;
import dev.josemariamerchan.controlweb.config.LoginRateLimitProperties;

/** The four buckets working together, with the production defaults and a Clock we move by hand. */
class LoginRateLimiterTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	private final TestClock clock = new TestClock();
	private LoginRateLimiter limiter;

	@BeforeEach
	void setUp() {
		clock.fix(T0);
		limiter = new LoginRateLimiter(defaults(), clock);
	}

	private static LoginRateLimitProperties defaults() {
		return new LoginRateLimitProperties(10, Duration.ofSeconds(6), 5, Duration.ofMinutes(3), 20, Duration.ofMinutes(3), 60,
				Duration.ofSeconds(1), 10_000);
	}

	private void failures(String ip, String email, int count) {
		for (int i = 0; i < count; i++) {
			limiter.admit(ip);
			limiter.checkAccount(email);
			limiter.recordFailure(ip, email);
		}
	}

	@Test
	@DisplayName("5 failures from one IP are allowed; the 6th attempt is refused with the time to wait")
	void sixthFailureFromOneIpIsRefused() {
		failures("1.1.1.1", "a@x.com", 5);

		assertThatThrownBy(() -> limiter.admit("1.1.1.1")).isInstanceOf(LoginRateLimitedException.class)
				.extracting(e -> ((LoginRateLimitedException) e).getRetryAfterSeconds()).isEqualTo(180L);
	}

	@Test
	@DisplayName("after 3 minutes one more attempt is allowed")
	void oneTokenBackAfterThreeMinutes() {
		failures("1.1.1.1", "a@x.com", 5);

		clock.fix(T0.plusSeconds(179));
		assertThatThrownBy(() -> limiter.admit("1.1.1.1")).isInstanceOf(LoginRateLimitedException.class);

		clock.fix(T0.plusSeconds(180));
		assertThatCode(() -> limiter.admit("1.1.1.1")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("successful logins spend no failure tokens: only the attempts-per-minute limit applies")
	void successesDoNotCountAsFailures() {
		for (int i = 0; i < 9; i++) {
			limiter.admit("1.1.1.1");
			limiter.checkAccount("a@x.com");
			// no recordFailure: the login worked
		}
		clock.fix(T0.plusSeconds(60));
		for (int i = 0; i < 9; i++) {
			limiter.admit("1.1.1.1");
			limiter.checkAccount("a@x.com");
		}

		assertThatCode(() -> limiter.admit("1.1.1.1")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("more than 10 attempts in a burst from one IP are refused, whatever their result")
	void attemptsPerIpBurst() {
		for (int i = 0; i < 10; i++) {
			limiter.admit("1.1.1.1");
		}

		assertThatThrownBy(() -> limiter.admit("1.1.1.1")).isInstanceOf(LoginRateLimitedException.class);
		assertThatCode(() -> limiter.admit("2.2.2.2")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("attempts per IP refill at 10 per minute (one every 6 s)")
	void attemptsPerIpRefill() {
		for (int i = 0; i < 10; i++) {
			limiter.admit("1.1.1.1");
		}

		clock.fix(T0.plusSeconds(6));
		assertThatCode(() -> limiter.admit("1.1.1.1")).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.admit("1.1.1.1")).isInstanceOf(LoginRateLimitedException.class);
	}

	@Test
	@DisplayName("the account bucket is shared by every IP: 20 failures from different IPs lock that account")
	void accountBucketAcrossIps() {
		for (int i = 0; i < 20; i++) {
			failures("10.0." + (i / 200) + "." + (i % 200 + 1), "admin@x.com", 1);
		}

		assertThatThrownBy(() -> limiter.checkAccount("admin@x.com")).isInstanceOf(LoginRateLimitedException.class);
		// ... and a clean IP is still admitted at the door, but cannot get past the account check
		assertThatCode(() -> limiter.admit("9.9.9.9")).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.checkAccount("admin@x.com")).isInstanceOf(LoginRateLimitedException.class);
	}

	@Test
	@DisplayName("the account bucket is per account: another email is not affected")
	void otherAccountIsNotAffected() {
		for (int i = 0; i < 20; i++) {
			limiter.recordFailure("10.0.0." + (i + 1), "admin@x.com");
		}

		assertThatThrownBy(() -> limiter.checkAccount("admin@x.com")).isInstanceOf(LoginRateLimitedException.class);
		assertThatCode(() -> limiter.checkAccount("other@x.com")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("an email that does not exist is limited exactly like the real one (no enumeration)")
	void unknownEmailsAreLimitedToo() {
		for (int i = 0; i < 20; i++) {
			limiter.recordFailure("10.0.0." + (i + 1), "ghost@x.com");
		}

		assertThatThrownBy(() -> limiter.checkAccount("ghost@x.com")).isInstanceOf(LoginRateLimitedException.class);
	}

	@Test
	@DisplayName("emails are compared after trim and lower-casing, and very long values cannot grow memory")
	void emailsAreNormalized() {
		for (int i = 0; i < 20; i++) {
			limiter.recordFailure("10.0.0." + (i + 1), i % 2 == 0 ? "Admin@X.com" : "  admin@x.COM ");
		}

		assertThatThrownBy(() -> limiter.checkAccount("admin@x.com")).isInstanceOf(LoginRateLimitedException.class);
		assertThatCode(() -> limiter.checkAccount("x".repeat(100_000))).doesNotThrowAnyException();
		assertThatCode(() -> limiter.recordFailure("1.1.1.1", null)).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkAccount(null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the account bucket gives one token back every 3 minutes")
	void accountRefill() {
		for (int i = 0; i < 20; i++) {
			limiter.recordFailure("10.0.0." + (i + 1), "admin@x.com");
		}

		clock.fix(T0.plus(Duration.ofMinutes(3)));
		assertThatCode(() -> limiter.checkAccount("admin@x.com")).doesNotThrowAnyException();
		limiter.recordFailure("10.0.1.1", "admin@x.com");
		assertThatThrownBy(() -> limiter.checkAccount("admin@x.com")).isInstanceOf(LoginRateLimitedException.class);
	}

	@Test
	@DisplayName("the global limit protects BCrypt: 60 attempts in a burst from many IPs, then refused")
	void globalLimit() {
		for (int i = 0; i < 60; i++) {
			limiter.admit("10.1." + (i / 200) + "." + (i % 200 + 1));
		}

		assertThatThrownBy(() -> limiter.admit("8.8.8.8")).isInstanceOf(LoginRateLimitedException.class);

		clock.fix(T0.plusSeconds(1));
		assertThatCode(() -> limiter.admit("8.8.8.8")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("an attempt refused for being too many does not spend failure tokens of anyone")
	void refusedAttemptsDoNotSpendFailureTokens() {
		for (int i = 0; i < 10; i++) {
			limiter.admit("1.1.1.1");
		}
		for (int i = 0; i < 20; i++) {
			assertThatThrownBy(() -> limiter.admit("1.1.1.1")).isInstanceOf(LoginRateLimitedException.class);
		}

		clock.fix(T0.plusSeconds(60));
		assertThatCode(() -> limiter.admit("1.1.1.1")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("reset empties every bucket (what a restart of the application does)")
	void resetEmptiesBuckets() {
		failures("1.1.1.1", "a@x.com", 5);
		for (int i = 0; i < 20; i++) {
			limiter.recordFailure("10.0.0." + (i + 1), "admin@x.com");
		}

		limiter.reset();

		assertThatCode(() -> limiter.admit("1.1.1.1")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkAccount("admin@x.com")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the message never says which limit was hit, the IP, or the email")
	void messageIsGeneric() {
		failures("1.1.1.1", "secret-admin@x.com", 5);

		assertThatThrownBy(() -> limiter.admit("1.1.1.1")).hasMessageNotContaining("1.1.1.1")
				.hasMessageNotContaining("secret-admin").hasMessageContaining("Too many login attempts");
	}
}
