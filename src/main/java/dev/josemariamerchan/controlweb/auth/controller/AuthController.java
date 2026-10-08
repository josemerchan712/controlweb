package dev.josemariamerchan.controlweb.auth.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.josemariamerchan.controlweb.auth.dto.LoginRequest;
import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.dto.MeResponse;
import dev.josemariamerchan.controlweb.auth.service.AuthService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request.email(), request.password());
	}

	/** The identity carried by the (already verified) access token. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		List<String> roles = jwt.getClaimAsStringList("roles");
		return new MeResponse(jwt.getSubject(), roles == null || roles.isEmpty() ? null : roles.get(0));
	}
}
