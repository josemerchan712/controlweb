package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import dev.josemariamerchan.controlweb.auth.config.AdminProperties;
import dev.josemariamerchan.controlweb.auth.service.AdminSeeder;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminSeederTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
	private static final String PASSWORD = "a-long-enough-password";

	@Mock
	private UserRepository users;

	@Captor
	private ArgumentCaptor<User> userCaptor;

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
	private AdminSeeder seeder;

	@BeforeEach
	void setUp() {
		seeder = new AdminSeeder(users, encoder, new AdminProperties("Admin@Example.com", PASSWORD),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("creates the admin from the environment with a BCrypt hash, never the plain password")
	void createsAdminWhenMissing() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());

		seeder.run(null);

		verify(users).save(userCaptor.capture());
		User saved = userCaptor.getValue();
		assertThat(saved.getEmail()).isEqualTo("admin@example.com");
		assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
		assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
		assertThat(encoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
	}

	@Test
	@DisplayName("does nothing when the admin already exists with the same password")
	void leavesExistingAdminAlone() {
		User existing = new User("admin@example.com", encoder.encode(PASSWORD), Role.ADMIN, NOW);
		String hashBefore = existing.getPasswordHash();
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

		seeder.run(null);

		verify(users, never()).save(any());
		assertThat(existing.getPasswordHash()).isEqualTo(hashBefore);
	}

	@Test
	@DisplayName("re-hashes the password when ADMIN_PASSWORD changed (rotation through the environment)")
	void rotatesPassword() {
		User existing = new User("admin@example.com", encoder.encode("the-previous-password"), Role.ADMIN, NOW);
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

		seeder.run(null);

		assertThat(encoder.matches(PASSWORD, existing.getPasswordHash())).isTrue();
		assertThat(encoder.matches("the-previous-password", existing.getPasswordHash())).isFalse();
		verify(users).save(existing);
	}
}
