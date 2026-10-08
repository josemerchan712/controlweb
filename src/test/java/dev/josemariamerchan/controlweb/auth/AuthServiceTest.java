package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import dev.josemariamerchan.controlweb.auth.dto.LoginResponse;
import dev.josemariamerchan.controlweb.auth.service.AuthService;
import dev.josemariamerchan.controlweb.auth.service.InvalidCredentialsException;
import dev.josemariamerchan.controlweb.auth.service.TokenService;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	@Mock
	private UserRepository users;

	@Mock
	private TokenService tokens;

	// Real BCrypt (low cost to keep the test fast), spied so we can see that it is always called.
	private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));

	private AuthService service;
	private User admin;

	@BeforeEach
	void setUp() {
		service = new AuthService(users, encoder, tokens);
		admin = new User("admin@example.com", encoder.encode("correct-password"), Role.ADMIN, NOW);
	}

	@Test
	@DisplayName("login with correct credentials issues a token")
	void loginSucceeds() {
		LoginResponse issued = new LoginResponse("jwt-value", NOW.plusSeconds(3600));
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
		when(tokens.issueFor(admin)).thenReturn(issued);

		assertThat(service.login("admin@example.com", "correct-password")).isSameAs(issued);
	}

	@Test
	@DisplayName("the email is trimmed and compared case-insensitively")
	void emailIsNormalized() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
		when(tokens.issueFor(admin)).thenReturn(new LoginResponse("jwt-value", NOW));

		service.login("  Admin@Example.COM ", "correct-password");

		verify(users).findByEmail("admin@example.com");
	}

	@Test
	@DisplayName("wrong password and unknown email fail with the SAME exception type and message")
	void sameFailureForWrongPasswordAndUnknownEmail() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
		when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

		Throwable wrongPassword = org.assertj.core.api.Assertions
				.catchThrowable(() -> service.login("admin@example.com", "wrong-password"));
		Throwable unknownEmail = org.assertj.core.api.Assertions
				.catchThrowable(() -> service.login("nobody@example.com", "whatever-password"));

		assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class);
		assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
		assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage()).isEqualTo("Invalid email or password");
	}

	@Test
	@DisplayName("for an unknown email a BCrypt comparison is still performed (against a dummy hash) to even out timing")
	void bcryptRunsEvenWhenUserDoesNotExist() {
		when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login("nobody@example.com", "whatever-password"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(encoder).matches(org.mockito.ArgumentMatchers.eq("whatever-password"), startsWith("$2"));
	}

	@Test
	@DisplayName("no token is issued on failure")
	void noTokenOnFailure() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

		assertThatThrownBy(() -> service.login("admin@example.com", "wrong-password"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(tokens, never()).issueFor(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("a password longer than BCrypt's 72 bytes is just invalid credentials, not an error")
	void overlongPasswordIsInvalidCredentials() {
		assertThatThrownBy(() -> service.login("admin@example.com", "x".repeat(100)))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(encoder, never()).matches(anyString(), anyString());
	}

	@Test
	@DisplayName("null or blank inputs are invalid credentials")
	void blankInputsAreInvalid() {
		assertThatThrownBy(() -> service.login(null, "x")).isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.login("admin@example.com", null)).isInstanceOf(InvalidCredentialsException.class);
	}
}
