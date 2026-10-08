package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import jakarta.persistence.EntityManager;

@PostgresDataJpaTest
class CheckRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private CheckRepository checks;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("persists a successful check including the certificate expiry")
	void persistsSuccessfulCheck() {
		Site site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));
		Instant certExpiry = Instant.parse("2026-06-01T00:00:00Z");

		Check saved = checks.saveAndFlush(Check.up(site, T0, 200, 135, certExpiry));
		em.clear();

		Check found = checks.findById(saved.getId()).orElseThrow();
		assertThat(found.isUp()).isTrue();
		assertThat(found.getStatusCode()).isEqualTo(200);
		assertThat(found.getLatencyMs()).isEqualTo(135);
		assertThat(found.getCertExpiresAt()).isEqualTo(certExpiry);
		assertThat(found.getErrorMessage()).isNull();
		assertThat(found.getCheckedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("persists a failed check with an error message and no status code")
	void persistsFailedCheck() {
		Site site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));

		Check saved = checks.saveAndFlush(Check.down(site, T0, null, null, "Connection refused"));
		em.clear();

		Check found = checks.findById(saved.getId()).orElseThrow();
		assertThat(found.isUp()).isFalse();
		assertThat(found.getStatusCode()).isNull();
		assertThat(found.getLatencyMs()).isNull();
		assertThat(found.getErrorMessage()).isEqualTo("Connection refused");
	}

	@Test
	@DisplayName("a failed check keeps the certificate expiry when the handshake had worked")
	void failedCheckKeepsCertificateExpiry() {
		Site site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));
		Instant certExpiry = Instant.parse("2026-06-01T00:00:00Z");

		Check saved = checks.saveAndFlush(Check.down(site, T0, 503, 90, "HTTP 503", certExpiry));
		em.clear();

		Check found = checks.findById(saved.getId()).orElseThrow();
		assertThat(found.isUp()).isFalse();
		assertThat(found.getStatusCode()).isEqualTo(503);
		assertThat(found.getCertExpiresAt()).isEqualTo(certExpiry);
	}
}
