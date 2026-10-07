package dev.josemariamerchan.controlweb.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import jakarta.persistence.EntityManager;

@PostgresDataJpaTest
class SiteRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private SiteRepository sites;

	@Autowired
	private CheckRepository checks;

	@Autowired
	private AlertRuleRepository rules;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("persists a site with its defaults")
	void persistsSiteWithDefaults() {
		Site saved = sites.saveAndFlush(new Site("Blog", "https://blog.example.com", 60, T0));
		em.clear();

		Site found = sites.findById(saved.getId()).orElseThrow();

		assertThat(found.getName()).isEqualTo("Blog");
		assertThat(found.getCheckIntervalSeconds()).isEqualTo(60);
		assertThat(found.isActive()).isTrue();
		assertThat(found.isPublicVisible()).isFalse();
		assertThat(found.getLastCheckedAt()).isNull();
		assertThat(found.getCreatedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("rejects two sites with the same URL")
	void rejectsDuplicateUrl() {
		sites.saveAndFlush(new Site("Blog", "https://blog.example.com", 60, T0));

		assertThatThrownBy(() -> sites.saveAndFlush(new Site("Blog 2", "https://blog.example.com", 60, T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("rejects a check interval below 30 seconds")
	void rejectsIntervalBelowMinimum() {
		assertThatThrownBy(() -> sites.saveAndFlush(new Site("Blog", "https://blog.example.com", 29, T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("deleting a site cascades to its checks, rules and incidents")
	void deleteCascadesToChildren() {
		Site site = sites.saveAndFlush(new Site("Blog", "https://blog.example.com", 60, T0));
		checks.save(Check.up(site, T0, 200, 120, null));
		rules.save(new AlertRule(site, IncidentType.DOWN, 2));
		incidents.save(Incident.open(site, IncidentType.DOWN, T0, "Down"));
		em.flush();
		// Detach the children so only the database (ON DELETE CASCADE) removes them.
		em.clear();

		sites.deleteById(site.getId());
		em.flush();
		em.clear();

		assertThat(checks.count()).isZero();
		assertThat(rules.count()).isZero();
		assertThat(incidents.count()).isZero();
	}
}
