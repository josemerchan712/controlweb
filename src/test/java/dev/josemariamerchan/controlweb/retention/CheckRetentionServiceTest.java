package dev.josemariamerchan.controlweb.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.josemariamerchan.controlweb.TestClock;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.config.RetentionProperties;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Deletes checks strictly OLDER than {@code days}; the Clock decides what "now" is. Runs against the real PostgreSQL.
 * Batch size is 100 here so that batching is cheap to observe.
 */
@SpringBootTest(properties = { "app.retention.days=90", "app.retention.batch-size=100", "app.retention.enabled=false" })
@Import(TestcontainersConfiguration.class)
class CheckRetentionServiceTest {

	private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
	private static final Instant CUTOFF = NOW.minus(Duration.ofDays(90));

	@Autowired private CheckRetentionService service;
	@Autowired private CheckRepository checks;
	@Autowired private SiteRepository sites;
	@Autowired private IncidentRepository incidents;
	@Autowired private TestClock clock;
	@Autowired private JdbcClient jdbc;
	@Autowired private RetentionProperties properties;

	private Site site;

	@BeforeEach
	void setUp() {
		clock.fix(NOW);
		cleanUp();
		site = sites.save(new Site("retention", "https://retention.example.com", 60, NOW.minus(Duration.ofDays(200))));
	}

	@AfterEach
	void tearDown() {
		clock.reset();
		cleanUp();
	}

	private void cleanUp() {
		checks.deleteAll();
		incidents.deleteAll();
		sites.deleteAll();
	}

	private List<Instant> remaining() {
		return checks.findAll().stream().map(Check::getCheckedAt).sorted().toList();
	}

	@Test
	@DisplayName("deletes checks older than the limit and keeps newer ones")
	void deletesOldKeepsNew() {
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(120)), 200, 100, null));
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(91)), 200, 100, null));
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(89)), 200, 100, null));
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(1)), 200, 100, null));

		long deleted = service.purgeExpired();

		assertThat(deleted).isEqualTo(2);
		assertThat(remaining()).containsExactly(NOW.minus(Duration.ofDays(89)), NOW.minus(Duration.ofDays(1)));
	}

	@Test
	@DisplayName("exact boundary: one microsecond older than the cutoff goes; exactly at the cutoff and just inside stay")
	void exactBoundary() {
		Instant justOutside = CUTOFF.minus(Duration.ofNanos(1000));
		Instant justInside = CUTOFF.plus(Duration.ofNanos(1000));
		checks.save(Check.up(site, justOutside, 200, 100, null));
		checks.save(Check.up(site, CUTOFF, 200, 100, null));
		checks.save(Check.up(site, justInside, 200, 100, null));

		long deleted = service.purgeExpired();

		assertThat(deleted).isEqualTo(1);
		assertThat(remaining()).containsExactly(CUTOFF, justInside);
	}

	@Test
	@DisplayName("works in batches: more expired rows than one batch are all deleted")
	void worksInBatches() {
		int expired = properties.batchSize() * 2 + 7;
		jdbc.sql("""
				insert into checks (site_id, checked_at, up, status_code, latency_ms)
				select :site, cast(:old as timestamptz) - (g * interval '1 second'), true, 200, 10
				from generate_series(1, :n) g
				""")
				.param("site", site.getId())
				.param("old", CUTOFF.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
				.param("n", expired)
				.update();
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(1)), 200, 100, null));

		long deleted = service.purgeExpired();

		assertThat(deleted).isEqualTo(expired);
		assertThat(checks.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("never touches incidents, old or not")
	void doesNotTouchIncidents() {
		incidents.save(Incident.open(site, IncidentType.DOWN, NOW.minus(Duration.ofDays(200)), "old"));
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(150)), 200, 100, null));

		service.purgeExpired();

		assertThat(incidents.count()).isEqualTo(1);
		assertThat(checks.count()).isZero();
	}

	@Test
	@DisplayName("nothing to delete: returns 0 and keeps everything")
	void nothingToDelete() {
		checks.save(Check.up(site, NOW.minus(Duration.ofDays(5)), 200, 100, null));

		assertThat(service.purgeExpired()).isZero();
		assertThat(checks.count()).isEqualTo(1);
	}
}
