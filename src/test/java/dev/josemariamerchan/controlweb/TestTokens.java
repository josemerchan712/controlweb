package dev.josemariamerchan.controlweb;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** Builds JWTs for tests, independently of the application's own token code. */
public final class TestTokens {

	public static final String ISSUER = "controlweb";

	private TestTokens() {
	}

	public static String token(String base64Secret, String subject, String issuer, Instant issuedAt,
			Instant expiresAt) {
		SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA256");
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(subject)
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.claim("roles", List.of("ADMIN"))
				.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	/** A valid token for the test admin, signed with the application's (test) secret. */
	public static String adminToken() {
		Instant now = Instant.now();
		return token(TestCredentials.JWT_SECRET, TestCredentials.ADMIN_EMAIL, ISSUER, now, now.plus(Duration.ofHours(1)));
	}

	/** Adds {@code Authorization: Bearer <valid admin token>} to a MockMvc request. */
	public static RequestPostProcessor bearer() {
		return bearer(adminToken());
	}

	public static RequestPostProcessor bearer(String token) {
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}
}
