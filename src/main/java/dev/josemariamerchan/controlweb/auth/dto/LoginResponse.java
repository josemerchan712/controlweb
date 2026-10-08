package dev.josemariamerchan.controlweb.auth.dto;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt) {

	@Override
	public String toString() {
		return "LoginResponse[token=<redacted>, expiresAt=" + expiresAt + "]";
	}
}
