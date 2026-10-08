package dev.josemariamerchan.controlweb.auth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import dev.josemariamerchan.controlweb.config.CorsProperties;
import dev.josemariamerchan.controlweb.config.PublicProperties;

/**
 * Default deny: everything needs a valid JWT except the few endpoints listed here (login, the public status
 * endpoint -- by exact path, so nothing else under /api/public/ is open -- and the health check). Adding a public
 * endpoint is a deliberate change to this class (and to the guard test that watches it).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** The one public endpoint, by exact path. */
	private static final String PUBLIC_STATUS_PATH = "/api/public/status";

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	@Order(2)
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				// No cookies or sessions: the token travels in the Authorization header, so there is no CSRF surface.
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						.requestMatchers(PUBLIC_STATUS_PATH).permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(bearerTokenResolver()).jwt(Customizer.withDefaults()));
		return http.build();
	}

	/**
	 * Reads the {@code Authorization: Bearer} token, EXCEPT on the public status endpoint. The token filter runs on
	 * every request, even on public paths, and rejects an invalid token with a 401. A visitor whose browser still
	 * sends an expired or garbage token (say, a dashboard whose session ran out) must still see the public page, so
	 * there the header is simply ignored. Every other path keeps requiring a valid token.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
		return request -> (request.getContextPath() + PUBLIC_STATUS_PATH).equals(request.getRequestURI())
				? null
				: standard.resolve(request);
	}

	/** API documentation is public ONLY when the dev profile is active. */
	@Bean
	@Order(1)
	@Profile("dev")
	SecurityFilterChain docsSecurity(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	/**
	 * Two separate CORS policies, each with exact origins only (no wildcards) and no credentials:
	 * <ul>
	 * <li>the PUBLIC status endpoint: GET only, from the origins in {@code app.public.cors-allowed-origins};</li>
	 * <li>the rest of the API: from the origins in {@code app.cors.allowed-origins}.</li>
	 * </ul>
	 * {@link UrlBasedCorsConfigurationSource} uses the FIRST pattern that matches, so the specific one is registered
	 * before the general one. A portfolio origin therefore cannot call the private API, nor the dashboard origin the
	 * public one, unless it is listed in both.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(CorsProperties privateApi, PublicProperties publicApi) {
		CorsConfiguration publicConfiguration = new CorsConfiguration();
		publicConfiguration.setAllowedOrigins(publicApi.corsAllowedOrigins());
		publicConfiguration.setAllowedMethods(List.of("GET"));
		publicConfiguration.setAllowedHeaders(List.of());
		publicConfiguration.setAllowCredentials(false);
		publicConfiguration.setMaxAge(Duration.ofHours(1));

		CorsConfiguration privateConfiguration = new CorsConfiguration();
		privateConfiguration.setAllowedOrigins(privateApi.allowedOrigins());
		privateConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		privateConfiguration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		privateConfiguration.setAllowCredentials(false);
		privateConfiguration.setMaxAge(Duration.ofHours(1));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/public/**", publicConfiguration);
		source.registerCorsConfiguration("/api/**", privateConfiguration);
		return source;
	}
}
