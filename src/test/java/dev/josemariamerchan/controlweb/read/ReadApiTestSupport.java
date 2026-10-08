package dev.josemariamerchan.controlweb.read;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestClock;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Seeding helpers for the read-API integration tests. Subclasses carry the Spring Boot test annotations
 * ({@code @SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)}).
 */
abstract class ReadApiTestSupport {

	/** All the seeded data is relative to this moment, which is also where the test clock is fixed. */
	protected static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	private static final AtomicInteger UNIQUE = new AtomicInteger();

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected SiteRepository sites;
	@Autowired
	protected CheckRepository checks;
	@Autowired
	protected IncidentRepository incidents;
	@Autowired
	protected JdbcClient jdbc;
	@Autowired
	protected TestClock clock;

	@AfterEach
	void cleanUpReadApiData() {
		clock.reset();
		sites.deleteAll(); // checks, rules and incidents go with them (ON DELETE CASCADE)
	}

	protected Site site(String name) {
		return site(name, true);
	}

	protected Site site(String name, boolean active) {
		String url = "https://site-%d.example.com".formatted(UNIQUE.incrementAndGet());
		return sites.saveAndFlush(new Site(name, url, 60, active, false, NOW.minusSeconds(86_400L * 365)));
	}

	protected Check up(Site site, Instant at, int latencyMs) {
		return checks.saveAndFlush(Check.up(site, at, 200, latencyMs, null));
	}

	protected Check down(Site site, Instant at) {
		return checks.saveAndFlush(Check.down(site, at, 503, 5000, "HTTP 503"));
	}

	/** A successful check without latency (the schema allows it; statistics must ignore it for latency). */
	protected void upWithoutLatency(Site site, Instant at) {
		insertCheck(site, at, true, 200, null);
	}

	protected void insertCheck(Site site, Instant at, boolean up, Integer statusCode, Integer latencyMs) {
		jdbc.sql("insert into checks (site_id, checked_at, up, status_code, latency_ms) values (:site, :at, :up, :status, :latency)")
				.param("site", site.getId()).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("up", up)
				.param("status", statusCode).param("latency", latencyMs).update();
	}

	/** {@code count} successful checks, one per minute starting one minute after {@code from}. Fast: one statement. */
	protected void seedChecks(Site site, Instant from, int count) {
		jdbc.sql("""
				insert into checks (site_id, checked_at, up, status_code, latency_ms)
				select :site, :from + g * interval '1 minute', true, 200, 100 + g
				from generate_series(1, :count) g
				""")
				.param("site", site.getId()).param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC)).param("count", count)
				.update();
	}

	protected Incident openIncident(Site site, IncidentType type, Instant since) {
		return incidents.saveAndFlush(Incident.open(site, type, since, "details of " + type));
	}

	protected Incident resolvedIncident(Site site, IncidentType type, Instant since, long lastedSeconds) {
		Incident incident = Incident.open(site, type, since, "details of " + type);
		incident.resolve(since.plusSeconds(lastedSeconds), "Site recovered");
		return incidents.saveAndFlush(incident);
	}
}
