package dev.josemariamerchan.controlweb.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.LoginRateLimitProperties;

/**
 * Decides whether a login attempt may be processed, with four token buckets (see {@link LoginRateLimitProperties}):
 * <ul>
 * <li><b>global</b>: attempts per minute of the whole server, so BCrypt cannot exhaust the CPU;</li>
 * <li><b>client attempts</b>: every attempt of one client counts, even a malformed one;</li>
 * <li><b>client failures</b>: failed logins of one client;</li>
 * <li><b>account failures</b>: failed logins against one email from ALL clients, whether or not the email exists
 * (so a 429 never reveals which emails are real).</li>
 * </ul>
 *
 * <p>Two steps because the account is only known after the body is parsed: {@link #admit} runs first (in a filter, before
 * parsing) and {@link #checkAccount} just before BCrypt. A refused attempt costs no CPU. Failure buckets are checked with
 * "peek" and spent by {@link #recordFailure} after BCrypt, so concurrent attempts can overshoot by at most the number of
 * requests in flight; successful logins spend no failure tokens.
 *
 * <p><b>Known limitation:</b> the account bucket can be drained by an attacker using many IP addresses, which blocks the
 * login even with the right password until tokens come back (or the application is restarted: the state is in memory).
 * See docs/DESPLIEGUE.md.
 */
@Component
public class LoginRateLimiter {

	private static final String GLOBAL_KEY = "*";
	private static final int MAX_EMAIL_KEY_LENGTH = 254;

	private final TokenBuckets global;
	private final TokenBuckets clientAttempts;
	private final TokenBuckets clientFailures;
	private final TokenBuckets accountFailures;

	public LoginRateLimiter(LoginRateLimitProperties limits, Clock clock) {
		this.global = new TokenBuckets(limits.globalAttempts(), limits.globalAttemptsRefill(), 1, clock);
		this.clientAttempts = new TokenBuckets(limits.ipAttempts(), limits.ipAttemptsRefill(), limits.maxKeys(), clock);
		this.clientFailures = new TokenBuckets(limits.ipFailures(), limits.ipFailuresRefill(), limits.maxKeys(), clock);
		this.accountFailures = new TokenBuckets(limits.accountFailures(), limits.accountFailuresRefill(), limits.maxKeys(),
				clock);
	}

	/**
	 * First gate, before the request body is read. Spends a global and a client attempt token and refuses a client whose
	 * failure tokens are used up.
	 *
	 * @param clientKey the client as returned by {@link ClientIpResolver#clientKey}
	 * @throws LoginRateLimitedException if a limit applies
	 */
	public void admit(String clientKey) {
		refuseIfWaiting(global.tryConsume(GLOBAL_KEY));
		refuseIfWaiting(clientAttempts.tryConsume(clientKey));
		refuseIfWaiting(clientFailures.peek(clientKey));
	}

	/**
	 * Second gate, once the email is known and BEFORE any password is checked.
	 *
	 * @throws LoginRateLimitedException if the account has had too many failed logins
	 */
	public void checkAccount(String email) {
		refuseIfWaiting(accountFailures.peek(accountKey(email)));
	}

	/** A password was wrong (or the email unknown): spend one failure token of the client and one of the account. */
	public void recordFailure(String clientKey, String email) {
		clientFailures.consume(clientKey);
		accountFailures.consume(accountKey(email));
	}

	/** Forgets everything. This is what restarting the application does; tests use it to start clean. */
	public void reset() {
		global.clear();
		clientAttempts.clear();
		clientFailures.clear();
		accountFailures.clear();
	}

	private static void refuseIfWaiting(Duration wait) {
		if (!wait.isZero()) {
			throw new LoginRateLimitedException(wait);
		}
	}

	private static String accountKey(String email) {
		if (email == null) {
			return "";
		}
		String key = email.strip().toLowerCase(Locale.ROOT);
		return key.length() > MAX_EMAIL_KEY_LENGTH ? key.substring(0, MAX_EMAIL_KEY_LENGTH) : key;
	}
}
