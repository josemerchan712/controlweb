package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;

/** Every way of presenting a bad token must be a 401; only a genuine one gets in. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JwtAuthenticationIntegrationTest {

	private static final String PROTECTED = "/api/sites";

	@Autowired
	private MockMvc mvc;

	private static String token(String secret, String subject, String issuer, Instant issuedAt, Instant expiresAt) {
		return TestTokens.token(secret, subject, issuer, issuedAt, expiresAt);
	}

	private void assertRejected(String authorizationHeader) throws Exception {
		mvc.perform(get(PROTECTED).header("Authorization", authorizationHeader))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
	}

	@Test
	@DisplayName("a valid token is accepted")
	void validTokenIsAccepted() throws Exception {
		mvc.perform(get(PROTECTED).with(TestTokens.bearer())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("no token at all: 401 with a Bearer challenge")
	void noTokenIs401() throws Exception {
		mvc.perform(get(PROTECTED))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
	}

	@Test
	@DisplayName("an expired token is rejected with 401")
	void expiredTokenIs401() throws Exception {
		Instant now = Instant.now();
		String expired = token(TestCredentials.JWT_SECRET, TestCredentials.ADMIN_EMAIL, TestTokens.ISSUER,
				now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));

		assertRejected("Bearer " + expired);
	}

	@Test
	@DisplayName("a token that expired one second ago is already rejected (no leeway)")
	void justExpiredTokenIs401() throws Exception {
		Instant now = Instant.now();
		String expired = token(TestCredentials.JWT_SECRET, TestCredentials.ADMIN_EMAIL, TestTokens.ISSUER,
				now.minusSeconds(60), now.minusSeconds(1));

		assertRejected("Bearer " + expired);
	}

	@Test
	@DisplayName("a token whose payload was tampered with (subject changed, signature kept) is rejected")
	void tamperedPayloadIs401() throws Exception {
		String[] parts = TestTokens.adminToken().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String forgedPayload = payload.replace(TestCredentials.ADMIN_EMAIL, "attacker@evil.test");
		assertThat(forgedPayload).isNotEqualTo(payload);
		String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

		assertRejected("Bearer " + forged);
	}

	@Test
	@DisplayName("a token whose expiry was extended after signing is rejected")
	void tamperedExpiryIs401() throws Exception {
		Instant now = Instant.now();
		String[] parts = token(TestCredentials.JWT_SECRET, TestCredentials.ADMIN_EMAIL, TestTokens.ISSUER,
				now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))).split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String forgedPayload = payload.replaceAll("\"exp\":\\d+", "\"exp\":" + now.plus(Duration.ofDays(365)).getEpochSecond());
		assertThat(forgedPayload).isNotEqualTo(payload);
		String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

		assertRejected("Bearer " + forged);
	}

	@Test
	@DisplayName("a token with a truncated or altered signature is rejected")
	void alteredSignatureIs401() throws Exception {
		String valid = TestTokens.adminToken();
		String flipped = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");

		assertRejected("Bearer " + flipped);
		assertRejected("Bearer " + valid.substring(0, valid.lastIndexOf('.') + 1));
	}

	@Test
	@DisplayName("a token signed with a different secret is rejected")
	void wrongSecretIs401() throws Exception {
		Instant now = Instant.now();
		String foreign = token(TestCredentials.OTHER_JWT_SECRET, TestCredentials.ADMIN_EMAIL, TestTokens.ISSUER, now,
				now.plus(Duration.ofHours(1)));

		assertRejected("Bearer " + foreign);
	}

	@Test
	@DisplayName("an unsigned token (alg=none) is rejected")
	void algNoneIs401() throws Exception {
		Instant now = Instant.now();
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = b64.encodeToString(("{\"iss\":\"controlweb\",\"sub\":\"" + TestCredentials.ADMIN_EMAIL
				+ "\",\"exp\":" + now.plus(Duration.ofHours(1)).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));

		assertRejected("Bearer " + header + "." + payload + ".");
	}

	@Test
	@DisplayName("a correctly signed token from another issuer is rejected")
	void wrongIssuerIs401() throws Exception {
		Instant now = Instant.now();
		String token = token(TestCredentials.JWT_SECRET, TestCredentials.ADMIN_EMAIL, "someone-else", now,
				now.plus(Duration.ofHours(1)));

		assertRejected("Bearer " + token);
	}

	@Test
	@DisplayName("garbage, empty and non-Bearer credentials are rejected")
	void malformedCredentialsAre401() throws Exception {
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
		mvc.perform(get(PROTECTED).header("Authorization", "Basic YWRtaW46YWRtaW4=")).andExpect(status().isUnauthorized());
		mvc.perform(get(PROTECTED).header("Authorization", TestTokens.adminToken())).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("the access token is not accepted as a query parameter")
	void tokenInQueryStringIsIgnored() throws Exception {
		mvc.perform(get(PROTECTED).queryParam("access_token", TestTokens.adminToken()))
				.andExpect(status().isUnauthorized());
	}
}
