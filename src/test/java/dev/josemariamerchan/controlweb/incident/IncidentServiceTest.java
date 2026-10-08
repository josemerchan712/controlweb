package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;
import dev.josemariamerchan.controlweb.incident.service.IncidentService;
import dev.josemariamerchan.controlweb.incident.service.IncidentWriter;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/** Glue between the database and the engine: what is read, what is applied, and what happens when applying fails. */
@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
	private static final Long SITE_ID = 1L;

	@Mock
	private SiteRepository sites;
	@Mock
	private CheckRepository checks;
	@Mock
	private AlertRuleRepository rules;
	@Mock
	private IncidentRepository incidents;
	@Mock
	private IncidentWriter writer;
	@Mock
	private PlatformTransactionManager transactionManager;

	private Site site;
	private IncidentService service;

	@BeforeEach
	void setUp() {
		lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new IncidentService(sites, checks, rules, incidents, new RuleEngine(3), writer, transactionManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
		site = new Site("Blog", "https://blog.example.com", 60, NOW.minusSeconds(86_400));
		lenient().when(sites.findById(SITE_ID)).thenReturn(Optional.of(site));
		lenient().when(rules.findAllBySiteId(SITE_ID)).thenReturn(List.of(new AlertRule(site, IncidentType.DOWN, 2)));
		lenient().when(incidents.findOpenTypes(SITE_ID)).thenReturn(List.of());
		lenient().when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of());
	}

	private Check failed(String error) {
		return Check.down(site, NOW, 500, null, error);
	}

	private Check ok() {
		return Check.up(site, NOW, 200, 100, null);
	}

	@Test
	@DisplayName("confirmed failures open an incident, with the evaluation time and an explanatory detail")
	void opensIncident() {
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(failed("HTTP 500"), failed("HTTP 500")));

		service.evaluate(SITE_ID);

		verify(writer).open(eq(SITE_ID), eq(IncidentType.DOWN), contains("2 consecutive failed checks"), eq(NOW));
		verify(writer, never()).close(anyLong(), any(), anyString(), any());
	}

	@Test
	@DisplayName("a successful check closes the open incident")
	void closesIncident() {
		when(incidents.findOpenTypes(SITE_ID)).thenReturn(List.of(IncidentType.DOWN));
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(ok(), failed("HTTP 500"), failed("HTTP 500")));

		service.evaluate(SITE_ID);

		verify(writer).close(eq(SITE_ID), eq(IncidentType.DOWN), contains("recovered"), eq(NOW));
		verify(writer, never()).open(anyLong(), any(), anyString(), any());
	}

	@Test
	@DisplayName("with the incident already open and the site still failing, nothing is written")
	void nothingToDoWhenAlreadyOpen() {
		when(incidents.findOpenTypes(SITE_ID)).thenReturn(List.of(IncidentType.DOWN));
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(failed("HTTP 500"), failed("HTTP 500"), failed("HTTP 500")));

		service.evaluate(SITE_ID);
		service.evaluate(SITE_ID);

		verifyNoInteractions(writer);
	}

	@Test
	@DisplayName("an unknown site is ignored")
	void unknownSite() {
		when(sites.findById(99L)).thenReturn(Optional.empty());

		assertThatCode(() -> service.evaluate(99L)).doesNotThrowAnyException();

		verifyNoInteractions(writer, checks, rules, incidents);
	}

	@Test
	@DisplayName("an inactive site is not evaluated: nothing opens even if its checks fail, leftovers close")
	void inactiveSite() {
		site.update("Blog", "https://blog.example.com", 60, false, false, NOW);
		when(incidents.findOpenTypes(SITE_ID)).thenReturn(List.of(IncidentType.SSL_EXPIRING));
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(failed("HTTP 500"), failed("HTTP 500"), failed("HTTP 500")));

		service.evaluate(SITE_ID);

		verify(writer).close(SITE_ID, IncidentType.SSL_EXPIRING, "Site deactivated", NOW);
		verify(writer, never()).open(anyLong(), any(), anyString(), any());
	}

	@Test
	@DisplayName("losing the race to open (unique index violation) is swallowed, no event is expected, and the rest still applies")
	void lostRaceIsSwallowedAndOthersContinue() {
		when(rules.findAllBySiteId(SITE_ID)).thenReturn(List.of(new AlertRule(site, IncidentType.DOWN, 1),
				new AlertRule(site, IncidentType.SSL_EXPIRING, 14)));
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(
				Check.down(site, NOW, 503, 90, "HTTP 503", NOW.plusSeconds(3 * 86_400))));
		doThrow(new DataIntegrityViolationException("uq_incidents_one_open_per_site_type")).when(writer)
				.open(eq(SITE_ID), eq(IncidentType.DOWN), anyString(), any());

		assertThatCode(() -> service.evaluate(SITE_ID)).doesNotThrowAnyException();

		verify(writer).open(eq(SITE_ID), eq(IncidentType.DOWN), anyString(), eq(NOW));
		verify(writer).open(eq(SITE_ID), eq(IncidentType.SSL_EXPIRING), contains("expires in"), eq(NOW));
	}

	@Test
	@DisplayName("an unexpected failure applying one decision does not stop the others nor propagate")
	void unexpectedFailureIsIsolated() {
		when(rules.findAllBySiteId(SITE_ID)).thenReturn(List.of(new AlertRule(site, IncidentType.DOWN, 1),
				new AlertRule(site, IncidentType.SSL_EXPIRING, 14)));
		when(checks.findRecent(eq(SITE_ID), any())).thenReturn(List.of(
				Check.down(site, NOW, 503, 90, "HTTP 503", NOW.plusSeconds(3 * 86_400))));
		doThrow(new IllegalStateException("connection reset")).when(writer).open(eq(SITE_ID), eq(IncidentType.DOWN), anyString(), any());

		assertThatCode(() -> service.evaluate(SITE_ID)).doesNotThrowAnyException();

		verify(writer).open(eq(SITE_ID), eq(IncidentType.SSL_EXPIRING), anyString(), eq(NOW));
	}

	@Test
	@DisplayName("loads at least 50 recent checks, or as many as the DOWN threshold needs")
	void windowSize() {
		service.evaluate(SITE_ID);
		verify(checks).findRecent(SITE_ID, PageRequest.of(0, 50));

		when(rules.findAllBySiteId(SITE_ID)).thenReturn(List.of(new AlertRule(site, IncidentType.DOWN, 80)));
		service.evaluate(SITE_ID);
		verify(checks).findRecent(SITE_ID, PageRequest.of(0, 80));
	}

	@Test
	@DisplayName("closeOpenIncidents delegates to the writer with the clock's time, to join the caller's transaction")
	void closeOpenIncidentsDelegates() {
		when(writer.closeAllOpen(SITE_ID, "Site deactivated", NOW)).thenReturn(2);

		assertThat(service.closeOpenIncidents(SITE_ID, "Site deactivated")).isEqualTo(2);
	}
}
