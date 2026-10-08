package dev.josemariamerchan.controlweb.auth.service;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.auth.config.AdminProperties;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

/**
 * Creates the administrator from {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD} at startup. There is
 * no registration endpoint. The environment stays the source of truth: if the password there
 * changes, the stored hash is updated on the next start.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AdminProperties properties;
	private final Clock clock;

	public AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder, AdminProperties properties, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		String email = properties.normalizedEmail();
		Optional<User> existing = users.findByEmail(email);
		if (existing.isEmpty()) {
			users.save(new User(email, passwordEncoder.encode(properties.password()), Role.ADMIN, clock.instant()));
			log.info("Created administrator user {}", email);
			return;
		}
		User admin = existing.get();
		if (!passwordEncoder.matches(properties.password(), admin.getPasswordHash())) {
			admin.changePasswordHash(passwordEncoder.encode(properties.password()));
			users.save(admin);
			log.info("Updated the password of administrator {} from the environment", email);
		}
	}
}
