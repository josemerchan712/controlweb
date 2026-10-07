package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import jakarta.persistence.EntityManager;

@PostgresDataJpaTest
class AlertRuleRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private AlertRuleRepository rules;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("persists a rule enabled by default")
	void persistsRule() {
		Site site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));

		AlertRule saved = rules.saveAndFlush(new AlertRule(site, IncidentType.HIGH_LATENCY, 2000));
		em.clear();

		AlertRule found = rules.findById(saved.getId()).orElseThrow();
		assertThat(found.getType()).isEqualTo(IncidentType.HIGH_LATENCY);
		assertThat(found.getThreshold()).isEqualTo(2000);
		assertThat(found.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("rejects two rules of the same type for the same site")
	void rejectsDuplicateRuleTypePerSite() {
		Site site = sites.save(new Site("Blog", "https://blog.example.com", 60, T0));
		rules.saveAndFlush(new AlertRule(site, IncidentType.DOWN, 2));

		assertThatThrownBy(() -> rules.saveAndFlush(new AlertRule(site, IncidentType.DOWN, 3)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
