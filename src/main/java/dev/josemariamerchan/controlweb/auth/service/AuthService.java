package dev.josemariamerchan.controlweb.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.auth.config.AdminProperties;
import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokens;

	/**
	 * A valid BCrypt hash (same cost as real ones, of a random value nobody knows) used to spend the
	 * same time verifying a password when the email does not exist.
	 */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * @throws InvalidCredentialsException for an unknown email AND for a wrong password, identically
	 */
	@Transactional(readOnly = true)
	public LoginResponse login(String email, String password) {
		if (email == null || email.isBlank() || password == null || password.isEmpty()
				|| password.getBytes(StandardCharsets.UTF_8).length > AdminProperties.MAX_PASSWORD_BYTES) {
			throw new InvalidCredentialsException();
		}
		Optional<User> user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
		// Always run one BCrypt comparison, so response time does not reveal whether the email exists.
		boolean passwordMatches = passwordEncoder.matches(password,
				user.map(User::getPasswordHash).orElse(dummyHash));
		if (user.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return tokens.issueFor(user.get());
	}
}
