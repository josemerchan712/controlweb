package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import dev.josemariamerchan.controlweb.IncidentEventRecorder;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.rules.Decision;
import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;
import dev.josemariamerchan.controlweb.incident.rules.RuleInput;
import dev.josemariamerchan.controlweb.incident.service.IncidentService;
import dev.josemariamerchan.controlweb.incident.service.IncidentWriter;
import dev.josemariamerchan.controlweb.monitoring.TestServer;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * The whole path with a real PostgreSQL and a real HTTP server: checks are run through the
 * application's own manual-check entry point, which stores the check and then evaluates the rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IncidentLifecycleIntegrationTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private SiteRepository sites;
	@Autowired
	private CheckRepository checks;
	@Autowired
	private IncidentRepository incidents;
	@Autowired
	private AlertRuleRepository rules;
	@Autowired
	private CheckRunner runner;
	@Autowired
	private IncidentService incidentService;
	@Autowired
	private IncidentWriter writer;
	@Autowired
	private IncidentEventRecorder recorder;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private Clock clock;

	private TestServer server;
	private final AtomicInteger httpStatus = new AtomicInteger(200);
	private final AtomicInteger delayMs = new AtomicInteger(0);

	@BeforeEach
	void startServer() throws Exception {
		recorder.clear();
		server = TestServer.http();
		server.on("/", exchange -> {
			try {
				Thread.sleep(delayMs.get());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(httpStatus.get(), -1);
		});
	}

	@AfterEach
	void cleanUp() {
		server.close();
		sites.deleteAll();
	}

	// ---- helpers ----

	private long createSite() throws Exception {
		String body = mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Lifecycle\", \"url\": \"%s\", \"checkIntervalSeconds\": 60}".formatted(server.url("/"))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	private void check(long siteId, int times) {
		for (int i = 0; i < times; i++) {
			runner.runManually(siteId);
		}
	}

	private List<Incident> openIncidents() {
		return incidents.findAll().stream().filter(Incident::isOpen).toList();
	}

	private void putRule(long siteId, String type, String json) throws Exception {
		mvc.perform(put("/api/sites/{id}/rules/{type}", siteId, type).with(TestTokens.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
	}

	// ---- the full cycle ----

	@Test
	@DisplayName("failed, failed -> DOWN incident opened; still failing -> no duplicate; successful -> incident closed")
	void fullDownCycle() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);

		check(siteId, 1);
		assertThat(incidents.count()).as("one failure is not confirmed (threshold 2)").isZero();

		check(siteId, 1);
		assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
			assertThat(incident.getType()).isEqualTo(IncidentType.DOWN);
			assertThat(incident.isOpen()).isTrue();
			assertThat(incident.getDetails()).contains("2 consecutive failed checks").contains("HTTP 500");
		});
		assertThat(recorder.opened()).hasSize(1);

		check(siteId, 2);
		assertThat(incidents.count()).as("still down: no second incident").isEqualTo(1);
		assertThat(recorder.opened()).as("and no second announcement").hasSize(1);

		httpStatus.set(200);
		check(siteId, 1);

		assertThat(openIncidents()).isEmpty();
		Incident closed = incidents.findAll().get(0);
		assertThat(closed.getResolvedAt()).isNotNull().isAfterOrEqualTo(closed.getOpenedAt());
		assertThat(closed.getDetails()).contains("Closed: Site recovered");
		assertThat(recorder.resolved()).singleElement().satisfies((IncidentResolved event) -> {
			assertThat(event.incidentId()).isEqualTo(closed.getId());
			assertThat(event.duration()).isEqualTo(Duration.between(closed.getOpenedAt(), closed.getResolvedAt()));
			assertThat(event.duration().isNegative()).isFalse();
		});
		assertThat(recorder.all()).extracting(Object::getClass).containsExactly(IncidentOpened.class, IncidentResolved.class);
		assertThat(checks.count()).as("every check was stored (1 + 1 + 2 + 1)").isEqualTo(5);
	}

	@Test
	@DisplayName("a new outage after a recovery is a new incident")
	void secondOutageIsANewIncident() throws Exception {
		long siteId = createSite();

		httpStatus.set(503);
		check(siteId, 2);
		httpStatus.set(200);
		check(siteId, 1);
		httpStatus.set(503);
		check(siteId, 2);

		assertThat(incidents.count()).isEqualTo(2);
		assertThat(openIncidents()).hasSize(1);
		assertThat(recorder.opened()).hasSize(2);
		assertThat(recorder.resolved()).hasSize(1);
	}

	@Test
	@DisplayName("HIGH_LATENCY: opens only after 3 consecutive slow checks, never for a single spike, and closes when fast again")
	void highLatencyCycle() throws Exception {
		long siteId = createSite();
		putRule(siteId, "HIGH_LATENCY", "{\"threshold\": 100}");

		delayMs.set(160);
		check(siteId, 2);
		assertThat(incidents.count()).as("two slow checks are not enough").isZero();

		delayMs.set(0);
		check(siteId, 1); // a fast one: the spike run is broken
		delayMs.set(160);
		check(siteId, 2);
		assertThat(incidents.count()).as("slow, slow, fast, slow, slow: no run of 3").isZero();

		check(siteId, 1);
		assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
			assertThat(incident.getType()).isEqualTo(IncidentType.HIGH_LATENCY);
			assertThat(incident.getDetails()).contains("last 3 successful checks").contains("threshold 100 ms");
		});

		delayMs.set(0);
		check(siteId, 1);
		assertThat(openIncidents()).isEmpty();
		assertThat(incidents.findAll().get(0).getDetails()).contains("Closed: Latency back to normal");
	}

	// ---- rules, site state ----

	@Test
	@DisplayName("disabling a rule does not touch incidents at once: the open one is closed at the NEXT check")
	void disabledRuleClosesAtNextCheck() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 2);
		assertThat(openIncidents()).hasSize(1);

		putRule(siteId, "DOWN", "{\"threshold\": 2, \"enabled\": false}");
		assertThat(openIncidents()).as("editing a rule never reevaluates by itself").hasSize(1);
		assertThat(recorder.resolved()).isEmpty();

		check(siteId, 1); // still failing, but the rule is off
		assertThat(openIncidents()).isEmpty();
		assertThat(incidents.findAll().get(0).getDetails()).contains("Closed: Rule disabled");
		assertThat(recorder.resolved()).hasSize(1);

		check(siteId, 3);
		assertThat(incidents.count()).as("a disabled rule never opens anything").isEqualTo(1);
	}

	@Test
	@DisplayName("changing a threshold applies at the next check, not immediately")
	void thresholdChangeAppliesAtNextCheck() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 1); // 1 failure, DOWN needs 2

		putRule(siteId, "DOWN", "{\"threshold\": 1}");
		assertThat(incidents.count()).as("nothing happens when the rule is edited").isZero();

		check(siteId, 1);
		assertThat(openIncidents()).hasSize(1);
	}

	@Test
	@DisplayName("deactivating a site closes its incidents immediately with 'Site deactivated', and an inactive site opens nothing")
	void deactivationClosesIncidentsAtOnce() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 2);
		assertThat(openIncidents()).hasSize(1);
		recorder.clear();

		mvc.perform(put("/api/sites/{id}", siteId).with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Lifecycle\", \"url\": \"%s\", \"checkIntervalSeconds\": 60, \"active\": false}"
						.formatted(server.url("/")))).andExpect(status().isOk());

		assertThat(openIncidents()).as("closed in the same request, without waiting for a check").isEmpty();
		assertThat(incidents.findAll().get(0).getDetails()).contains("Closed: Site deactivated");
		assertThat(recorder.resolved()).hasSize(1);

		check(siteId, 3); // manual checks of an inactive site are allowed, but evaluate nothing
		assertThat(incidents.count()).isEqualTo(1);
		assertThat(recorder.opened()).isEmpty();
	}

	@Test
	@DisplayName("updating an active site without deactivating it leaves its incidents alone")
	void updateWithoutDeactivationKeepsIncidents() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 2);

		mvc.perform(put("/api/sites/{id}", siteId).with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Renamed\", \"url\": \"%s\", \"checkIntervalSeconds\": 120}".formatted(server.url("/"))))
				.andExpect(status().isOk());

		assertThat(openIncidents()).hasSize(1);
		assertThat(recorder.resolved()).isEmpty();
	}

	// ---- idempotence and concurrency ----

	@Test
	@DisplayName("evaluating the same data again and again, even from a brand new service, never duplicates anything")
	void evaluationIsIdempotent() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 2);
		assertThat(incidents.count()).isEqualTo(1);

		for (int i = 0; i < 3; i++) {
			incidentService.evaluate(siteId);
		}
		IncidentService afterRestart = new IncidentService(sites, checks, rules, incidents, new RuleEngine(3), writer,
				transactionManager, clock); // a "restarted" application: no memory, only the database
		afterRestart.evaluate(siteId);

		assertThat(incidents.count()).isEqualTo(1);
		assertThat(recorder.opened()).hasSize(1);
		assertThat(recorder.resolved()).isEmpty();
	}

	@Test
	@DisplayName("two evaluations racing to open the same incident: one incident, one event, no error")
	void concurrentEvaluationsOpenOnce() throws Exception {
		long siteId = createSite();
		Site site = sites.findById(siteId).orElseThrow();
		Instant now = Instant.now();
		checks.save(Check.down(site, now.minusSeconds(20), 500, null, "HTTP 500"));
		checks.save(Check.down(site, now.minusSeconds(10), 500, null, "HTTP 500"));
		recorder.clear();

		// Both evaluations read the database and decide to OPEN before either one writes.
		CyclicBarrier bothHaveDecided = new CyclicBarrier(2);
		List<Decision> decided = new ArrayList<>();
		RuleEngine engineThatWaitsForTheOther = new RuleEngine(3) {
			@Override
			public List<Decision> evaluate(RuleInput input) {
				List<Decision> decisions = super.evaluate(input);
				synchronized (decided) {
					decided.addAll(decisions);
				}
				try {
					bothHaveDecided.await(10, TimeUnit.SECONDS);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				return decisions;
			}
		};
		IncidentService service = new IncidentService(sites, checks, rules, incidents, engineThatWaitsForTheOther, writer,
				transactionManager, clock);

		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> service.evaluate(siteId));
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> service.evaluate(siteId));
		first.get(20, TimeUnit.SECONDS);
		second.get(20, TimeUnit.SECONDS);

		assertThat(decided).as("both evaluations really decided to open").hasSize(2)
				.allMatch(decision -> decision instanceof Decision.Open);
		assertThat(incidents.findAll()).as("the unique index let exactly one through").hasSize(1);
		assertThat(openIncidents()).hasSize(1);
		assertThat(recorder.opened()).as("and only the winner announced it").hasSize(1);
	}

	@Test
	@DisplayName("two evaluations racing to close the same incident: closed once, announced once")
	void concurrentEvaluationsCloseOnce() throws Exception {
		long siteId = createSite();
		httpStatus.set(500);
		check(siteId, 2);
		Site site = sites.findById(siteId).orElseThrow();
		checks.save(Check.up(site, Instant.now().plusSeconds(1), 200, 50, null)); // recovery, not yet evaluated
		recorder.clear();

		CyclicBarrier bothHaveDecided = new CyclicBarrier(2);
		RuleEngine engineThatWaitsForTheOther = new RuleEngine(3) {
			@Override
			public List<Decision> evaluate(RuleInput input) {
				List<Decision> decisions = super.evaluate(input);
				try {
					bothHaveDecided.await(10, TimeUnit.SECONDS);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				return decisions;
			}
		};
		IncidentService service = new IncidentService(sites, checks, rules, incidents, engineThatWaitsForTheOther, writer,
				transactionManager, clock);

		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> service.evaluate(siteId));
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> service.evaluate(siteId));
		first.get(20, TimeUnit.SECONDS);
		second.get(20, TimeUnit.SECONDS);

		assertThat(openIncidents()).isEmpty();
		assertThat(recorder.resolved()).as("closed and announced exactly once").hasSize(1);
	}
}
