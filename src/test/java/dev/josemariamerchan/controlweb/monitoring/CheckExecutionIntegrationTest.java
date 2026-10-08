package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.zaxxer.hikari.HikariDataSource;

import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.monitoring.scheduler.CheckScheduler;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;
import dev.josemariamerchan.controlweb.monitoring.service.InFlightGuard;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Whole path with a real PostgreSQL and real HTTP servers: the manual endpoint, the periodic cycle
 * (called directly, the background scheduler is disabled in tests) and their interaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckExecutionIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private CheckRepository checks;

	@Autowired
	private CheckRunner runner;

	@Autowired
	private InFlightGuard guard;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private ApplicationContext context;

	private final List<TestServer> servers = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		servers.forEach(TestServer::close);
		sites.deleteAll();
	}

	private TestServer server() throws Exception {
		TestServer server = TestServer.http();
		servers.add(server);
		return server;
	}

	private Site site(String name, String url) {
		return site(name, url, true, null);
	}

	private Site site(String name, String url, boolean active, Instant lastCheckedAt) {
		Site site = new Site(name, url, 60, active, false, Instant.now().minus(Duration.ofDays(1)));
		if (lastCheckedAt != null) {
			site.markChecked(lastCheckedAt);
		}
		return sites.saveAndFlush(site);
	}

	private static String closedPortUrl() throws Exception {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return "http://localhost:" + socket.getLocalPort();
		}
	}

	private Site reload(Site site) {
		return sites.findById(site.getId()).orElseThrow();
	}

	// ---- wiring ----

	@Test
	@DisplayName("with app.scheduler.enabled=false (test profile) there is no background scheduler")
	void schedulerIsOffInTests() {
		assertThat(context.getBeansOfType(CheckScheduler.class)).isEmpty();
		assertThat(context.getBeansOfType(CheckRunner.class)).hasSize(1);
	}

	// ---- manual check ----

	@Test
	@DisplayName("POST /api/sites/{id}/check runs now, returns the result and stores it")
	void manualCheckStoresTheResult() throws Exception {
		TestServer server = server();
		server.respond("/ok", 200);
		Site site = site("ok", server.url("/ok"));

		mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.siteId").value(site.getId()))
				.andExpect(jsonPath("$.up").value(true))
				.andExpect(jsonPath("$.statusCode").value(200))
				.andExpect(jsonPath("$.latencyMs").isNumber())
				.andExpect(jsonPath("$.checkedAt").isNotEmpty());

		assertThat(checks.findAll()).singleElement().satisfies(check -> {
			assertThat(check.isUp()).isTrue();
			assertThat(check.getStatusCode()).isEqualTo(200);
		});
		assertThat(reload(site).getLastCheckedAt()).isNotNull();
		assertThat(guard.size()).isZero();
	}

	@Test
	@DisplayName("a failing site is still a 200 for the endpoint: the failure is the stored result, and last_checked_at moves")
	void manualCheckOfFailingSites() throws Exception {
		TestServer server = server();
		server.respond("/down", 503);
		Site http503 = site("http503", server.url("/down"));
		Site refused = site("refused", closedPortUrl());

		mvc.perform(post("/api/sites/{id}/check", http503.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.up").value(false))
				.andExpect(jsonPath("$.statusCode").value(503))
				.andExpect(jsonPath("$.errorMessage").value("HTTP 503"));
		mvc.perform(post("/api/sites/{id}/check", refused.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.up").value(false))
				.andExpect(jsonPath("$.statusCode").doesNotExist())
				.andExpect(jsonPath("$.errorMessage").value("Connection failed: refused or unreachable"));

		assertThat(checks.count()).isEqualTo(2);
		assertThat(reload(http503).getLastCheckedAt()).isNotNull();
		assertThat(reload(refused).getLastCheckedAt()).as("updated even though the check failed").isNotNull();
	}

	@Test
	@DisplayName("a manual check also works for an inactive site")
	void manualCheckOfInactiveSite() throws Exception {
		TestServer server = server();
		server.respond("/", 200);
		Site paused = site("paused", server.url("/"), false, null);

		mvc.perform(post("/api/sites/{id}/check", paused.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.up").value(true));
	}

	@Test
	@DisplayName("a manual check of an unknown site is a 404, and without a token a 401")
	void manualCheckNotFoundAndUnauthorized() throws Exception {
		mvc.perform(post("/api/sites/{id}/check", 999_999).with(TestTokens.bearer()))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/sites/{id}/check", 1)).andExpect(status().isUnauthorized());
		assertThat(guard.size()).isZero();
	}

	@Test
	@DisplayName("a manual check while another check of the same site is running answers 409, then the first one finishes normally")
	void manualCheckWhileInFlightIsConflict() throws Exception {
		TestServer server = server();
		CountDownLatch arrived = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.on("/slow", exchange -> {
			arrived.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		Site site = site("slow", server.url("/slow"));

		CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
			try {
				return mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		assertThat(arrived.await(10, TimeUnit.SECONDS)).as("the first check reached the server").isTrue();

		mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("A check of site %d is already in progress".formatted(site.getId())));

		release.countDown();
		assertThat(first.get(10, TimeUnit.SECONDS)).contains("\"up\":true");
		assertThat(checks.count()).as("the refused request stored nothing").isEqualTo(1);
		assertThat(guard.size()).isZero();
		mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer())).andExpect(status().isOk());
	}

	// ---- no database connection during the network call ----

	@Test
	@DisplayName("no database connection is held while the site is being requested")
	void noConnectionHeldDuringNetworkCall() throws Exception {
		HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
		AtomicInteger activeDuringRequest = new AtomicInteger(-1);
		AtomicInteger totalDuringRequest = new AtomicInteger(-1);
		TestServer server = server();
		server.on("/probe", exchange -> {
			activeDuringRequest.set(pool.getHikariPoolMXBean().getActiveConnections());
			totalDuringRequest.set(pool.getHikariPoolMXBean().getTotalConnections());
			exchange.sendResponseHeaders(200, -1);
		});
		Site site = site("probe", server.url("/probe"));

		mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer())).andExpect(status().isOk());

		assertThat(totalDuringRequest.get()).as("the pool was running").isGreaterThan(0);
		assertThat(activeDuringRequest.get()).as("connections checked out while the request was in flight").isZero();
	}

	// ---- the periodic cycle ----

	@Test
	@DisplayName("a cycle checks the due sites only; a second cycle right after finds nothing due")
	void cycleChecksOnlyDueSites() throws Exception {
		TestServer server = server();
		server.respond("/a", 200);
		server.respond("/c", 200);
		Site neverChecked = site("never", server.url("/a"));
		Site overdue = site("overdue", server.url("/c"), true, Instant.now().minus(Duration.ofHours(1)));
		Site notDue = site("not-due", server.url("/b"), true, Instant.now().minusSeconds(5));
		Site inactive = site("inactive", server.url("/d"), false, null);
		Instant notDueBefore = reload(notDue).getLastCheckedAt();

		runner.runCycle();

		assertThat(checks.findAll()).extracting(check -> check.getSite().getId())
				.containsExactlyInAnyOrder(neverChecked.getId(), overdue.getId());
		assertThat(reload(neverChecked).getLastCheckedAt()).isNotNull();
		assertThat(reload(overdue).getLastCheckedAt()).isAfter(Instant.now().minusSeconds(30));
		assertThat(reload(notDue).getLastCheckedAt()).isEqualTo(notDueBefore);
		assertThat(reload(inactive).getLastCheckedAt()).isNull();
		assertThat(server.requests()).extracting(TestServer.RecordedRequest::path).containsExactlyInAnyOrder("/a", "/c");

		runner.runCycle();

		assertThat(checks.count()).as("nothing is due right after a cycle").isEqualTo(2);
		assertThat(guard.size()).isZero();
	}

	@Test
	@DisplayName("a site that fails does not stop the others from being checked and stored")
	void failingSiteDoesNotBlockOthers() throws Exception {
		TestServer server = server();
		server.respond("/ok", 200);
		Site refused = site("refused", closedPortUrl());
		Site unresolvable = site("unresolvable", "http://nope.invalid");
		Site ok = site("ok", server.url("/ok"));

		runner.runCycle();

		assertThat(checks.findAll()).hasSize(3);
		assertThat(checks.findAll()).filteredOn(Check::isUp).extracting(check -> check.getSite().getId())
				.containsExactly(ok.getId());
		for (Site site : List.of(refused, unresolvable, ok)) {
			assertThat(reload(site).getLastCheckedAt()).as("last_checked_at of %s", site.getName()).isNotNull();
		}
	}

	@Test
	@DisplayName("a site deleted while it is being checked is dropped quietly and the other sites are unaffected")
	void siteDeletedDuringCycle() throws Exception {
		TestServer server = server();
		server.respond("/ok", 200);
		Site doomed = site("doomed", "http://localhost:" + server.port() + "/doomed");
		server.on("/doomed", exchange -> {
			sites.deleteById(doomed.getId()); // the owner deletes the site mid-check
			exchange.sendResponseHeaders(200, -1);
		});
		Site survivor = site("survivor", server.url("/ok"));

		runner.runCycle();

		assertThat(sites.findById(doomed.getId())).isEmpty();
		assertThat(checks.findAll()).singleElement().satisfies(check -> {
			assertThat(check.getSite().getId()).isEqualTo(survivor.getId());
			assertThat(check.isUp()).isTrue();
		});
		assertThat(guard.size()).isZero();
	}

	@Test
	@DisplayName("a cycle and a manual check of the same site never overlap")
	void cycleAndManualCheckDoNotOverlap() throws Exception {
		TestServer server = server();
		CountDownLatch arrived = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.on("/slow", exchange -> {
			arrived.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		Site site = site("slow", server.url("/slow"));

		CompletableFuture<Void> cycle = CompletableFuture.runAsync(runner::runCycle);
		assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue();

		mvc.perform(post("/api/sites/{id}/check", site.getId()).with(TestTokens.bearer()))
				.andExpect(status().isConflict());
		release.countDown();
		cycle.get(10, TimeUnit.SECONDS);

		assertThat(server.requests()).as("only one request ever reached the site").hasSize(1);
		assertThat(checks.count()).isEqualTo(1);
	}
}
