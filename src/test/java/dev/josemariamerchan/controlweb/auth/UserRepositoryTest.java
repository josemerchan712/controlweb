package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

@PostgresDataJpaTest
class UserRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private UserRepository users;

	@Test
	@DisplayName("finds a user by email")
	void findsByEmail() {
		users.saveAndFlush(new User("admin@example.com", "$2a$10$hash", Role.ADMIN, T0));

		assertThat(users.findByEmail("admin@example.com"))
				.hasValueSatisfying(user -> assertThat(user.getRole()).isEqualTo(Role.ADMIN));
		assertThat(users.findByEmail("nobody@example.com")).isEmpty();
	}

	@Test
	@DisplayName("rejects two users with the same email")
	void rejectsDuplicateEmail() {
		users.saveAndFlush(new User("admin@example.com", "$2a$10$hash", Role.ADMIN, T0));

		assertThatThrownBy(() -> users.saveAndFlush(new User("admin@example.com", "$2a$10$other", Role.ADMIN, T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
