package dev.josemariamerchan.controlweb.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import dev.josemariamerchan.controlweb.auth.config.JwtConfig;
import dev.josemariamerchan.controlweb.auth.config.JwtProperties;
import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.user.User;

/** Issues signed access tokens. They carry the email, role and timestamps, never the password. */
@Service
public class TokenService {

	private final JwtEncoder encoder;
	private final JwtProperties properties;
	private final Clock clock;

	public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public LoginResponse issueFor(User user) {
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(Duration.ofMinutes(properties.ttlMinutes()));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(JwtConfig.ISSUER)
				.subject(user.getEmail())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.claim("roles", List.of(user.getRole().name()))
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new LoginResponse(token, expiresAt);
	}
}
