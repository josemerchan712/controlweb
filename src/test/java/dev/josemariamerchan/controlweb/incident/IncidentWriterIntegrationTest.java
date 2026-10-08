package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.josemariamerchan.controlweb.IncidentEventRecorder;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.service.IncidentWriter;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/** Each change in its own transaction, announced only after it commits, and never twice. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentWriterIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	@Autowired
	private IncidentWriter writer;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private IncidentEventRecorder recorder;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Site site;

	@BeforeEach
	void createSite() {
		recorder.clear();
		site = sites.saveAndFlush(new Site("Blog", "https://blog.example.com/secret-path?token=NOPE", 60, T0));
	}

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	@Test
	@DisplayName("open stores the incident and announces it once, after commit, with the site name (not its URL)")
	void openStoresAndAnnounces() {
		writer.open(site.getId(), IncidentType.DOWN, "Site is down", T0);

		assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
			assertThat(incident.getType()).isEqualTo(IncidentType.DOWN);
			assertThat(incident.isOpen()).isTrue();
			assertThat(incident.getOpenedAt()).isEqualTo(T0);
		});
		assertThat(recorder.opened()).singleElement().satisfies(event -> {
			assertThat(event.siteId()).isEqualTo(site.getId());
			assertThat(event.siteName()).isEqualTo("Blog");
			assertThat(event.type()).isEqualTo(IncidentType.DOWN);
			assertThat(event.openedAt()).isEqualTo(T0);
			assertThat(event.details()).isEqualTo("Site is down");
			assertThat(event.toString()).doesNotContain("secret-path").doesNotContain("NOPE");
		});
	}

	@Test
	@DisplayName("a second open of the same type is refused by the unique index: nothing stored, nothing announced")
	void secondOpenIsRefused() {
		writer.open(site.getId(), IncidentType.DOWN, "first", T0);

		assertThatThrownBy(() -> writer.open(site.getId(), IncidentType.DOWN, "second", T0.plusSeconds(30)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(incidents.count()).isEqualTo(1);
		assertThat(incidents.findAll().get(0).getDetails()).isEqualTo("first");
		assertThat(recorder.opened()).hasSize(1);
	}

	@Test
	@DisplayName("incidents of different types, and a new one after the previous was closed, are fine")
	void differentTypesAndReopening() {
		writer.open(site.getId(), IncidentType.DOWN, "down", T0);
		writer.open(site.getId(), IncidentType.HIGH_LATENCY, "slow", T0);
		writer.close(site.getId(), IncidentType.DOWN, "Site recovered", T0.plusSeconds(60));
		writer.open(site.getId(), IncidentType.DOWN, "down again", T0.plusSeconds(120));

		assertThat(incidents.count()).isEqualTo(3);
		assertThat(incidents.findOpenTypes(site.getId())).containsExactlyInAnyOrder(IncidentType.DOWN, IncidentType.HIGH_LATENCY);
		assertThat(recorder.opened()).hasSize(3);
		assertThat(recorder.resolved()).hasSize(1);
	}

	@Test
	@DisplayName("close ends the incident, records why, and announces it with its duration")
	void closeAnnouncesWithDuration() {
		writer.open(site.getId(), IncidentType.DOWN, "Site is down", T0);
		recorder.clear();

		boolean closed = writer.close(site.getId(), IncidentType.DOWN, "Site recovered", T0.plusSeconds(754));

		assertThat(closed).isTrue();
		Incident incident = incidents.findAll().get(0);
		assertThat(incident.isOpen()).isFalse();
		assertThat(incident.getResolvedAt()).isEqualTo(T0.plusSeconds(754));
		assertThat(incident.getDetails()).isEqualTo("Site is down | Closed: Site recovered");
		assertThat(recorder.resolved()).singleElement().satisfies((IncidentResolved event) -> {
			assertThat(event.incidentId()).isEqualTo(incident.getId());
			assertThat(event.duration()).isEqualTo(Duration.ofSeconds(754));
			assertThat(event.openedAt()).isEqualTo(T0);
			assertThat(event.resolvedAt()).isEqualTo(T0.plusSeconds(754));
			assertThat(event.siteName()).isEqualTo("Blog");
			assertThat(event.details()).contains("Closed: Site recovered");
		});
	}

	@Test
	@DisplayName("closing twice closes once and announces once")
	void closeIsExactlyOnce() {
		writer.open(site.getId(), IncidentType.DOWN, "Site is down", T0);
		recorder.clear();

		boolean first = writer.close(site.getId(), IncidentType.DOWN, "Site recovered", T0.plusSeconds(60));
		boolean second = writer.close(site.getId(), IncidentType.DOWN, "Site recovered", T0.plusSeconds(90));

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(recorder.resolved()).hasSize(1);
		assertThat(incidents.findAll().get(0).getResolvedAt()).isEqualTo(T0.plusSeconds(60));
	}

	@Test
	@DisplayName("closing a type that has nothing open does nothing")
	void closeWithNothingOpen() {
		assertThat(writer.close(site.getId(), IncidentType.SSL_EXPIRING, "Rule disabled", T0)).isFalse();
		assertThat(recorder.all()).isEmpty();
	}

	@Test
	@DisplayName("closeAllOpen closes every open incident of the site and announces each one")
	void closeAllOpen() {
		Site other = sites.saveAndFlush(new Site("Shop", "https://shop.example.com", 60, T0));
		writer.open(site.getId(), IncidentType.DOWN, "down", T0);
		writer.open(site.getId(), IncidentType.SSL_EXPIRING, "ssl", T0);
		writer.open(other.getId(), IncidentType.DOWN, "other site", T0);
		recorder.clear();

		int closed = new TransactionTemplate(transactionManager)
				.execute(status -> writer.closeAllOpen(site.getId(), "Site deactivated", T0.plusSeconds(10)));

		assertThat(closed).isEqualTo(2);
		assertThat(incidents.findOpenTypes(site.getId())).isEmpty();
		assertThat(incidents.findOpenTypes(other.getId())).containsExactly(IncidentType.DOWN);
		assertThat(recorder.resolved()).hasSize(2).allSatisfy(event -> assertThat(event.details()).contains("Closed: Site deactivated"));
	}

	@Test
	@DisplayName("closeAllOpen joins the caller's transaction: if it rolls back, nothing is closed and nothing announced")
	void closeAllOpenRollsBackWithCaller() {
		writer.open(site.getId(), IncidentType.DOWN, "down", T0);
		recorder.clear();

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertThat(writer.closeAllOpen(site.getId(), "Site deactivated", T0.plusSeconds(10))).isEqualTo(1);
			status.setRollbackOnly();
		});

		assertThat(incidents.findOpenTypes(site.getId())).containsExactly(IncidentType.DOWN);
		assertThat(recorder.all()).as("AFTER_COMMIT listeners never see a rolled-back change").isEmpty();
	}

	@Test
	@DisplayName("details are sanitized and capped before they reach the database and the event")
	void detailsAreSanitized() {
		writer.open(site.getId(), IncidentType.DOWN, "Last error for https://blog.example.com/p?token=SECRET123 and "
				+ "https://u:hunter2@blog.example.com " + "z".repeat(900), T0);

		Incident stored = incidents.findAll().get(0);
		assertThat(stored.getDetails()).hasSize(500).doesNotContain("SECRET123").doesNotContain("hunter2");
		assertThat(recorder.opened().get(0).details()).isEqualTo(stored.getDetails());
	}

	@Test
	@DisplayName("opening an incident for a site that no longer exists fails cleanly")
	void openForMissingSite() {
		assertThatThrownBy(() -> writer.open(999_999L, IncidentType.DOWN, "x", T0)).isInstanceOf(SiteNotFoundException.class);
		assertThat(recorder.all()).isEmpty();
	}

	@Test
	@DisplayName("sanity: the recorded event types are the public event records")
	void eventTypes() {
		writer.open(site.getId(), IncidentType.DOWN, "x", T0);
		writer.close(site.getId(), IncidentType.DOWN, "y", T0.plusSeconds(1));

		assertThat(recorder.all()).hasSize(2);
		assertThat(recorder.all().get(0)).isInstanceOf(IncidentOpened.class);
		assertThat(recorder.all().get(1)).isInstanceOf(IncidentResolved.class);
	}
}
