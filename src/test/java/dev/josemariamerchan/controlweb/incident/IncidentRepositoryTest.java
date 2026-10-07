package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Proves that "no repeated alerts while an incident is open" is guaranteed by the database itself
 * (partial unique index on incidents(site_id, type) WHERE resolved_at IS NULL), not only by
 * application logic that could race.
 */
@PostgresDataJpaTest
class IncidentRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private SiteRepository sites;

	private Site site;

	@BeforeEach
	void createSite() {
		site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));
	}

	@Test
	@DisplayName("rejects a second OPEN incident of the same type for the same site")
	void rejectsTwoOpenIncidentsOfSameTypeForSameSite() {
		incidents.saveAndFlush(Incident.open(site, IncidentType.DOWN, T0, "Connection refused"));

		assertThatThrownBy(() -> incidents.saveAndFlush(
				Incident.open(site, IncidentType.DOWN, T0.plusSeconds(60), "Connection refused again")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("allows opening a new incident of the same type once the previous one is resolved")
	void allowsNewIncidentAfterPreviousIsResolved() {
		Incident first = Incident.open(site, IncidentType.DOWN, T0, "Connection refused");
		first.resolve(T0.plusSeconds(300));
		incidents.saveAndFlush(first);

		assertThatCode(() -> incidents.saveAndFlush(
				Incident.open(site, IncidentType.DOWN, T0.plusSeconds(600), "Down again")))
				.doesNotThrowAnyException();

		assertThat(incidents.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("allows several RESOLVED incidents of the same type for the same site")
	void allowsManyResolvedIncidentsOfSameType() {
		for (int i = 0; i < 3; i++) {
			Incident incident = Incident.open(site, IncidentType.HIGH_LATENCY, T0.plusSeconds(i * 1000L), "Slow");
			incident.resolve(T0.plusSeconds(i * 1000L + 100));
			incidents.saveAndFlush(incident);
		}

		assertThat(incidents.count()).isEqualTo(3);
	}

	@Test
	@DisplayName("allows open incidents of different types for the same site")
	void allowsOpenIncidentsOfDifferentTypesForSameSite() {
		incidents.saveAndFlush(Incident.open(site, IncidentType.DOWN, T0, "Down"));

		assertThatCode(() -> incidents.saveAndFlush(
				Incident.open(site, IncidentType.SSL_EXPIRING, T0, "Certificate expires in 3 days")))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("allows open incidents of the same type for different sites")
	void allowsOpenIncidentsOfSameTypeForDifferentSites() {
		Site other = sites.save(new Site("Shop", "https://shop.example.com", 60, T0));
		incidents.saveAndFlush(Incident.open(site, IncidentType.DOWN, T0, "Down"));

		assertThatCode(() -> incidents.saveAndFlush(Incident.open(other, IncidentType.DOWN, T0, "Down")))
				.doesNotThrowAnyException();
	}
}
