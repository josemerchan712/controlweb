package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import dev.josemariamerchan.controlweb.monitoring.checker.CheckResult;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpSiteChecker;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.monitoring.service.CheckService;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

@ExtendWith(MockitoExtension.class)
class CheckServiceTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
	private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
	private static final Instant CERT_EXPIRY = Instant.parse("2026-05-01T00:00:00Z");
	private static final String URL = "https://blog.example.com";

	@Mock
	private SiteRepository sites;

	@Mock
	private CheckRepository checks;

	@Mock
	private HttpSiteChecker checker;

	@Mock
	private PlatformTransactionManager transactionManager;

	private final List<String> events = new ArrayList<>();
	private final List<TransactionDefinition> definitions = new ArrayList<>();
	private CheckService service;
	private Site site;

	@BeforeEach
	void setUp() {
		// Record transaction boundaries so tests can prove WHEN transactions are open.
		lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
			events.add("begin");
			definitions.add(invocation.getArgument(0));
			return new SimpleTransactionStatus();
		});
		lenient().doAnswer(invocation -> {
			events.add("commit");
			return null;
		}).when(transactionManager).commit(any(TransactionStatus.class));
		lenient().when(checks.saveAndFlush(any(Check.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service = new CheckService(sites, checks, checker, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
		site = new Site("Blog", URL, 60, EARLIER);
		lenient().when(sites.findById(1L)).thenReturn(Optional.of(site));
	}

	private void checkerReturns(CheckResult result) {
		when(checker.check(URL)).thenAnswer(invocation -> {
			events.add("network");
			return result;
		});
	}

	// ---- storing the outcome ----

	@Test
	@DisplayName("a successful check is stored with status, latency and certificate expiry, and the site is marked checked")
	void storesSuccessfulCheck() {
		checkerReturns(CheckResult.succeeded(200, 135, CERT_EXPIRY));

		CheckResponse response = service.runCheck(1L);

		ArgumentCaptor<Check> saved = ArgumentCaptor.forClass(Check.class);
		verify(checks).saveAndFlush(saved.capture());
		Check check = saved.getValue();
		assertThat(check.isUp()).isTrue();
		assertThat(check.getStatusCode()).isEqualTo(200);
		assertThat(check.getLatencyMs()).isEqualTo(135);
		assertThat(check.getCertExpiresAt()).isEqualTo(CERT_EXPIRY);
		assertThat(check.getCheckedAt()).isEqualTo(NOW);
		assertThat(check.getSite()).isSameAs(site);
		assertThat(site.getLastCheckedAt()).isEqualTo(NOW);
		assertThat(response.up()).isTrue();
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.certExpiresAt()).isEqualTo(CERT_EXPIRY);
	}

	@Test
	@DisplayName("a check that failed over HTTP keeps the certificate expiry (the TLS handshake worked)")
	void failedHttpCheckKeepsCertificateExpiry() {
		checkerReturns(CheckResult.failed(503, 90, "HTTP 503", CERT_EXPIRY));

		CheckResponse response = service.runCheck(1L);

		ArgumentCaptor<Check> saved = ArgumentCaptor.forClass(Check.class);
		verify(checks).saveAndFlush(saved.capture());
		assertThat(saved.getValue().isUp()).isFalse();
		assertThat(saved.getValue().getStatusCode()).isEqualTo(503);
		assertThat(saved.getValue().getErrorMessage()).isEqualTo("HTTP 503");
		assertThat(saved.getValue().getCertExpiresAt()).isEqualTo(CERT_EXPIRY);
		assertThat(response.certExpiresAt()).isEqualTo(CERT_EXPIRY);
	}

	@Test
	@DisplayName("last_checked_at is updated even when the check failed completely (no response at all)")
	void lastCheckedAtUpdatedOnTotalFailure() {
		checkerReturns(CheckResult.failed("Connection failed: refused or unreachable"));

		service.runCheck(1L);

		ArgumentCaptor<Check> saved = ArgumentCaptor.forClass(Check.class);
		verify(checks).saveAndFlush(saved.capture());
		assertThat(saved.getValue().isUp()).isFalse();
		assertThat(saved.getValue().getStatusCode()).isNull();
		assertThat(saved.getValue().getLatencyMs()).isNull();
		assertThat(saved.getValue().getErrorMessage()).isEqualTo("Connection failed: refused or unreachable");
		assertThat(site.getLastCheckedAt()).isEqualTo(NOW);
	}

	// ---- transactions ----

	@Test
	@DisplayName("no transaction is open during the network call: read tx, commit, network, write tx, commit")
	void noTransactionDuringNetworkCall() {
		checkerReturns(CheckResult.succeeded(200, 10, null));

		service.runCheck(1L);

		assertThat(events).containsExactly("begin", "commit", "network", "begin", "commit");
		assertThat(definitions.get(0).isReadOnly()).as("the first transaction only reads").isTrue();
		assertThat(definitions.get(1).isReadOnly()).as("the second one writes").isFalse();
	}

	// ---- sites that disappear ----

	@Test
	@DisplayName("an unknown site is NotFound and nothing is checked")
	void unknownSite() {
		when(sites.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.runCheck(99L)).isInstanceOf(SiteNotFoundException.class);

		verifyNoInteractions(checker);
		verify(checks, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("a site deleted while it was being checked: NotFound and nothing is saved")
	void siteDeletedDuringCheck() {
		when(sites.findById(1L)).thenReturn(Optional.of(site), Optional.empty());
		checkerReturns(CheckResult.succeeded(200, 10, null));

		assertThatThrownBy(() -> service.runCheck(1L)).isInstanceOf(SiteNotFoundException.class);

		verify(checks, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("a site deleted between loading it and committing (foreign key violation) is NotFound too")
	void siteDeletedAtCommit() {
		checkerReturns(CheckResult.succeeded(200, 10, null));
		when(checks.saveAndFlush(any(Check.class))).thenThrow(new DataIntegrityViolationException("fk"));
		when(sites.existsById(1L)).thenReturn(false);

		assertThatThrownBy(() -> service.runCheck(1L)).isInstanceOf(SiteNotFoundException.class);
	}

	@Test
	@DisplayName("a data integrity error while the site still exists is not disguised as NotFound")
	void otherIntegrityErrorsPropagate() {
		checkerReturns(CheckResult.succeeded(200, 10, null));
		when(checks.saveAndFlush(any(Check.class))).thenThrow(new DataIntegrityViolationException("other"));
		when(sites.existsById(1L)).thenReturn(true);

		assertThatThrownBy(() -> service.runCheck(1L)).isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- due sites ----

	@Test
	@DisplayName("due sites are selected with the injected clock's current instant")
	void dueSitesUseTheClock() {
		when(sites.findDueSiteIds(NOW)).thenReturn(List.of(3L, 1L));

		assertThat(service.findDueSiteIds()).containsExactly(3L, 1L);
	}
}
