package dev.josemariamerchan.controlweb.auth.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import dev.josemariamerchan.controlweb.auth.dto.LoginRequest;
import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.dto.MeResponse;
import dev.josemariamerchan.controlweb.auth.ratelimit.ClientIpResolver;
import dev.josemariamerchan.controlweb.auth.ratelimit.LoginRateLimiter;
import dev.josemariamerchan.controlweb.auth.service.AuthService;
import dev.josemariamerchan.controlweb.auth.service.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Tag(name = "Authentication", description = "Login and identity of the current token")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;
	private final LoginRateLimiter rateLimiter;
	private final ClientIpResolver clients;

	public AuthController(AuthService authService, LoginRateLimiter rateLimiter, ClientIpResolver clients) {
		this.authService = authService;
		this.rateLimiter = rateLimiter;
		this.clients = clients;
	}

	@Operation(summary = "Log in",
			description = "Exchanges the administrator email and password for a short-lived access token. The same error is returned for an unknown email and a wrong password. Rate limited per client and per account: 429 with Retry-After.")
	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		// The client was already admitted by LoginRateLimitFilter (before the body was parsed). The per-account
		// limit needs the email, so it is checked here, BEFORE any password is compared.
		rateLimiter.checkAccount(request.email());
		try {
			return authService.login(request.email(), request.password());
		}
		catch (InvalidCredentialsException ex) {
			rateLimiter.recordFailure(clients.clientKey(http), request.email());
			throw ex;
		}
	}

	/** The identity carried by the (already verified) access token. */
	@Operation(summary = "Current user",
			description = "Returns the identity carried by the access token.")
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		List<String> roles = jwt.getClaimAsStringList("roles");
		return new MeResponse(jwt.getSubject(), roles == null || roles.isEmpty() ? null : roles.get(0));
	}
}
