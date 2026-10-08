package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
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

import dev.josemariamerchan.controlweb.RecordingNotifier;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.monitoring.TestServer;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * From a real check to the notifier, through the real database, events and background sender: an incident
 * that opens and closes produces exactly one "opened" alert and one "resolved" alert.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlertLifecycleIntegrationTest {

	private static final Duration PATIENCE = Duration.ofSeconds(5);

	@Autowired
	private MockMvc mvc;
	@Autowired
	private SiteRepository sites;
	@Autowired
	private CheckRepository checks;
	@Autowired
	private IncidentRepository incidents;
	@Autowired
	private CheckRunner runner;
	@Autowired
	private RecordingNotifier notifier;

	private TestServer server;
	private final AtomicInteger httpStatus = new AtomicInteger(200);

	@BeforeEach
	void start() throws Exception {
		notifier.reset();
		server = TestServer.http();
		server.on("/", exchange -> exchange.sendResponseHeaders(httpStatus.get(), -1));
	}

	@AfterEach
	void cleanUp() {
		server.close();
		sites.deleteAll();
	}

	private long createSite(String name) throws Exception {
		String body = mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"url\": \"%s\", \"checkIntervalSeconds\": 60}".formatted(name, server.url("/"))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	private void check(long siteId, int times) {
		for (int i = 0; i < times; i++) {
			runner.runManually(siteId);
		}
	}

	@Test
	@DisplayName("an incident opened and then closed produces exactly one opening alert and one closing alert")
	void oneAlertEachWay() throws Exception {
		long siteId = createSite("Mi <Blog> & Co");
		httpStatus.set(500);

		check(siteId, 2); // DOWN opens at the second failure
		List<String> afterOpening = notifier.awaitMessages(1, PATIENCE);
		check(siteId, 3); // still down: no more alerts
		httpStatus.set(200);
		check(siteId, 1); // recovery
		List<String> afterClosing = notifier.awaitMessages(2, PATIENCE);
		Thread.sleep(300); // give a (wrong) extra alert time to show up
		List<String> all = notifier.messages();

		assertThat(afterOpening).hasSize(1);
		assertThat(afterClosing).hasSize(2);
		assertThat(all).as("exactly two alerts in total").hasSize(2);
		assertThat(all.get(0)).contains("Incidencia abierta").contains("Caída del sitio")
				.contains("Mi &lt;Blog&gt; &amp; Co").contains("HTTP 500");
		assertThat(all.get(1)).contains("Incidencia resuelta").contains("Caída del sitio")
				.contains("Mi &lt;Blog&gt; &amp; Co").containsPattern("Duración:</b> \\d+ (s|min)");
		assertThat(String.join("\n", all)).as("never the URL, only the name").doesNotContain("localhost")
				.doesNotContain("http://").doesNotContain("<Blog>");
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a notifier that fails (returns an error or throws) changes nothing: checks and incidents are intact")
	void notifierFailureDoesNotAffectChecksOrIncidents() throws Exception {
		long siteId = createSite("Blog");
		httpStatus.set(500);
		notifier.failWith(new IllegalStateException("Telegram exploded"));

		check(siteId, 2);
		notifier.awaitMessages(1, PATIENCE);
		httpStatus.set(200);
		check(siteId, 1);
		notifier.awaitMessages(2, PATIENCE);
		notifier.failWith(null);
		notifier.respondWith(SendResult.failed("Timed out talking to Telegram"));
		httpStatus.set(500);
		check(siteId, 2);
		notifier.awaitMessages(3, PATIENCE);

		assertThat(checks.count()).as("every check was stored").isEqualTo(5);
		assertThat(incidents.findAll()).as("both outages were recorded as incidents").hasSize(2);
		assertThat(incidents.findAll().stream().filter(incident -> incident.isOpen())).hasSize(1);
		assertThat(sites.findById(siteId).orElseThrow().getLastCheckedAt()).isNotNull();
	}

	@Test
	@DisplayName("the manual check endpoint still answers 200 with its result while the notifier is failing")
	void manualCheckSurvivesNotifierFailure() throws Exception {
		long siteId = createSite("Blog");
		httpStatus.set(500);
		notifier.failWith(new IllegalStateException("Telegram exploded"));

		check(siteId, 1);
		mvc.perform(post("/api/sites/{id}/check", siteId).with(TestTokens.bearer())).andExpect(status().isOk());

		assertThat(incidents.count()).isEqualTo(1);
		notifier.awaitMessages(1, PATIENCE);
	}
}
