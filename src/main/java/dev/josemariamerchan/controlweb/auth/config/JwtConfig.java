package dev.josemariamerchan.controlweb.auth.config;

import java.time.Clock;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** Symmetric (HS256) signing and verification of access tokens. */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	public static final String ISSUER = "controlweb";

	private static SecretKey key(JwtProperties properties) {
		return new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(JwtProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key(properties)));
	}

	/**
	 * Accepts only HS256 tokens signed with our secret (so {@code alg=none} and other algorithms are
	 * refused), issued by us, and not expired -- with no clock-skew leeway, since the issuer and the
	 * verifier are the same application.
	 */
	@Bean
	public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
		return decoder;
	}
}
