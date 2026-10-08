package dev.josemariamerchan.controlweb.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.josemariamerchan.controlweb.TestClock;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;

/**
 * POST /api/auth/login through the whole stack with the PRODUCTION limits (the rest of the suite runs with huge ones)
 * and a Clock we move by hand. 10.77.0.10 plays the role of Caddy.
 */
@SpringBootTest(properties = {
		"app.proxy.trusted-addresses=10.77.0.10",
		"app.login-rate-limit.ip-attempts=10",
		"app.login-rate-limit.ip-attempts-refill=6s",
		"app.login-rate-limit.ip-failures=5",
		"app.login-rate-limit.ip-failures-refill=3m",
		"app.login-rate-limit.account-failures=20",
		"app.login-rate-limit.account-failures-refill=3m",
		"app.login-rate-limit.global-attempts=60",
		"app.login-rate-limit.global-attempts-refill=1s" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginRateLimitIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
	private static final String CADDY = "10.77.0.10";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestClock clock;

	@Autowired
	private LoginRateLimiter limiter;

	@MockitoSpyBean
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void setUp() {
		clock.fix(T0);
		limiter.reset();
		clearInvocations(passwordEncoder);
	}

	@AfterEach
	void tearDown() {
		clock.reset();
		limiter.reset();
	}

	private static String body(String email, String password) {
		return "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password);
	}

	private MockHttpServletRequestBuilder login(String peer, String forwardedFor, String email, String password) {
		MockHttpServletRequestBuilder request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(body(email, password));
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return request.with(r -> {
			r.setRemoteAddr(peer);
			return r;
		});
	}

	private MockHttpServletRequestBuilder wrong(String peer) {
		return login(peer, null, TestCredentials.ADMIN_EMAIL, "definitely-wrong-password");
	}

	private MockHttpServletRequestBuilder right(String peer) {
		return login(peer, null, TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD);
	}

	@Test
	@DisplayName("5 wrong passwords from one IP answer 401; the 6th attempt answers 429 with Retry-After and a problem detail")
	void sixthFailureIsRefused() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(wrong("203.0.113.9")).andExpect(status().isUnauthorized());
		}

		mvc.perform(wrong("203.0.113.9"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "180"))
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Too many login attempts")));
	}

	@Test
	@DisplayName("even the RIGHT password is refused while the IP is blocked, and works again when the wait is over")
	void blockedThenAllowedAgain() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(wrong("203.0.113.9")).andExpect(status().isUnauthorized());
		}
		mvc.perform(right("203.0.113.9")).andExpect(status().isTooManyRequests());

		clock.fix(T0.plusSeconds(179));
		mvc.perform(right("203.0.113.9")).andExpect(status().isTooManyRequests());

		clock.fix(T0.plusSeconds(400));
		mvc.perform(right("203.0.113.9")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a successful login does not spend failure tokens")
	void successDoesNotCount() throws Exception {
		for (int i = 0; i < 4; i++) {
			mvc.perform(wrong("203.0.113.9")).andExpect(status().isUnauthorized());
		}
		mvc.perform(right("203.0.113.9")).andExpect(status().isOk());
		mvc.perform(wrong("203.0.113.9")).andExpect(status().isUnauthorized()); // the 5th failure
		mvc.perform(right("203.0.113.9")).andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("other IPs are not affected by one blocked IP")
	void otherIpsAreIndependent() throws Exception {
		for (int i = 0; i < 6; i++) {
			mvc.perform(wrong("203.0.113.9"));
		}

		mvc.perform(right("203.0.113.10")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a blocked attempt never reaches BCrypt (a rejected request costs no CPU)")
	void blockedAttemptsSkipBcrypt() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(wrong("203.0.113.9")).andExpect(status().isUnauthorized());
		}
		clearInvocations(passwordEncoder);

		mvc.perform(wrong("203.0.113.9")).andExpect(status().isTooManyRequests());
		mvc.perform(right("203.0.113.9")).andExpect(status().isTooManyRequests());

		verify(passwordEncoder, never()).matches(any(), any());
	}

	@Test
	@DisplayName("account limit: 20 failures from 20 different IPs block that account, even for the right password (known limitation)")
	void accountLimitBlocksEvenTheRightPassword() throws Exception {
		for (int i = 0; i < 20; i++) {
			mvc.perform(wrong("198.51.100." + (i + 1))).andExpect(status().isUnauthorized());
		}
		clearInvocations(passwordEncoder);

		mvc.perform(right("192.0.2.1"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
		verify(passwordEncoder, never()).matches(any(), any());

		clock.fix(T0.plusSeconds(180));
		mvc.perform(right("192.0.2.1")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a restart (empty buckets) is the way out of an account lockout")
	void resetIsTheEscapeHatch() throws Exception {
		for (int i = 0; i < 20; i++) {
			mvc.perform(wrong("198.51.100." + (i + 1))).andExpect(status().isUnauthorized());
		}
		mvc.perform(right("192.0.2.1")).andExpect(status().isTooManyRequests());

		limiter.reset();

		mvc.perform(right("192.0.2.1")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("an email that does not exist is limited and answered exactly like the real one")
	void unknownEmailIsIndistinguishable() throws Exception {
		for (int i = 0; i < 20; i++) {
			mvc.perform(login("198.51.100." + (i + 1), null, "ghost@example.com", "whatever-password-1"))
					.andExpect(status().isUnauthorized());
			mvc.perform(wrong("203.0.113." + (i + 1))).andExpect(status().isUnauthorized());
		}

		MvcResult ghost = mvc.perform(login("192.0.2.1", null, "ghost@example.com", "whatever-password-1"))
				.andExpect(status().isTooManyRequests()).andReturn();
		MvcResult real = mvc.perform(login("192.0.2.2", null, TestCredentials.ADMIN_EMAIL, "wrong-again-password"))
				.andExpect(status().isTooManyRequests()).andReturn();

		assertThat(ghost.getResponse().getContentAsString()).isEqualTo(real.getResponse().getContentAsString());
		assertThat(ghost.getResponse().getHeader("Retry-After")).isEqualTo(real.getResponse().getHeader("Retry-After"));
	}

	@Test
	@DisplayName("malformed requests also count: 10 broken bodies from one IP, the 11th is refused before parsing")
	void malformedBodiesCount() throws Exception {
		for (int i = 0; i < 10; i++) {
			mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{bad")
					.with(r -> {
						r.setRemoteAddr("203.0.113.50");
						return r;
					})).andExpect(status().isBadRequest());
		}

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{bad")
				.with(r -> {
					r.setRemoteAddr("203.0.113.50");
					return r;
				})).andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("X-Forwarded-For from a NON-trusted peer cannot be used to dodge the limit")
	void forgedHeaderFromUntrustedPeer() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(login("203.0.113.9", "1.1.1." + i, TestCredentials.ADMIN_EMAIL, "definitely-wrong-password"))
					.andExpect(status().isUnauthorized());
		}

		mvc.perform(login("203.0.113.9", "1.1.1.200", TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("behind the trusted proxy each real client has its own limit, and clients cannot prepend a fake address")
	void clientsBehindTheProxy() throws Exception {
		for (int i = 0; i < 5; i++) {
			// the client tries to hide behind a different address every time; only the rightmost entry (Caddy's) counts
			mvc.perform(login(CADDY, "9.9.9." + i + ", 198.51.100.7", TestCredentials.ADMIN_EMAIL, "definitely-wrong-password"))
					.andExpect(status().isUnauthorized());
		}

		mvc.perform(login(CADDY, "9.9.9.99, 198.51.100.7", TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD))
				.andExpect(status().isTooManyRequests());
		mvc.perform(login(CADDY, "198.51.100.8", TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("IPv6 clients of the same /64 share one limit")
	void ipv6SameSubnetSharesTheLimit() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(login(CADDY, "2001:db8:1:2:" + (i + 1) + "::1", TestCredentials.ADMIN_EMAIL, "definitely-wrong-password"))
					.andExpect(status().isUnauthorized());
		}

		mvc.perform(login(CADDY, "2001:db8:1:2:ffff::9", TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("only POST /api/auth/login is limited: other endpoints are untouched by the limiter")
	void otherEndpointsAreNotLimited() throws Exception {
		for (int i = 0; i < 80; i++) {
			mvc.perform(get("/api/auth/me").with(TestTokens.bearer()).with(r -> {
				r.setRemoteAddr("203.0.113.9");
				return r;
			})).andExpect(status().isOk());
		}
	}
}
