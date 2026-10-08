package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.auth.config.JwtConfig;
import dev.josemariamerchan.controlweb.auth.config.JwtProperties;
import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.service.TokenService;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;

class TokenServiceTest {

	private static final Instant NOW = Instant.now();
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final JwtProperties properties = new JwtProperties(TestCredentials.JWT_SECRET, 30);
	private final JwtConfig config = new JwtConfig();
	private final TokenService service = new TokenService(config.jwtEncoder(properties), properties, CLOCK);
	private final User admin = new User("admin@example.com", "hash", Role.ADMIN, NOW);

	@Test
	@DisplayName("issues an HS256 token with subject, role, issuer and a lifetime of ttl-minutes")
	void issuesTokenWithExpectedClaims() {
		LoginResponse response = service.issueFor(admin);

		JwtDecoder decoder = config.jwtDecoder(properties, CLOCK);
		Jwt jwt = decoder.decode(response.token());

		assertThat(jwt.getSubject()).isEqualTo("admin@example.com");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("controlweb");
		assertThat(jwt.<java.util.List<String>>getClaim("roles")).containsExactly("ADMIN");
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(30 * 60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(30 * 60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
	}

	@Test
	@DisplayName("the token carries no password or hash")
	void tokenDoesNotContainSensitiveData() {
		String token = service.issueFor(admin).token();
		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

		assertThat(payload).doesNotContain("hash").doesNotContain("password");
	}
}
