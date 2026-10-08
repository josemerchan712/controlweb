package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;

import dev.josemariamerchan.controlweb.SqlStatementCounter;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.site.domain.Site;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatusApiIntegrationTest extends ReadApiTestSupport {

	private static final Instant T = NOW.minus(Duration.ofHours(1));

	private String statusJson() throws Exception {
		return mvc.perform(get("/api/status").with(TestTokens.bearer())).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
	}

	private String stateOf(String json, String siteName) {
		List<String> states = JsonPath.read(json, "$.sites[?(@.name == '" + siteName + "')].status");
		assertThat(states).as("site %s on the board", siteName).hasSize(1);
		return states.get(0);
	}

	// ---- every derived state ----

	@Test
	@DisplayName("each derived state: UP, DEGRADED (latency / ssl), DOWN (incident / failed check), PENDING and PAUSED")
	void everyDerivedState() throws Exception {
		Site healthy = site("healthy");
		up(healthy, T, 120);

		Site slow = site("slow");
		up(slow, T, 3000);
		openIncident(slow, IncidentType.HIGH_LATENCY, T);

		Site expiring = site("expiring");
		up(expiring, T, 100);
		openIncident(expiring, IncidentType.SSL_EXPIRING, T);

		Site downByIncident = site("down-by-incident");
		up(downByIncident, T, 100); // the last check is fine, but the DOWN incident is still open
		openIncident(downByIncident, IncidentType.DOWN, T.minusSeconds(120));

		Site downByCheck = site("down-by-check");
		down(downByCheck, T); // failed last check, no incident yet (below the threshold)

		site("pending");

		Site paused = site("paused", false);
		down(paused, T);
		openIncident(paused, IncidentType.DOWN, T);

		String json = statusJson();

		assertThat(stateOf(json, "healthy")).isEqualTo("UP");
		assertThat(stateOf(json, "slow")).isEqualTo("DEGRADED");
		assertThat(stateOf(json, "expiring")).isEqualTo("DEGRADED");
		assertThat(stateOf(json, "down-by-incident")).isEqualTo("DOWN");
		assertThat(stateOf(json, "down-by-check")).isEqualTo("DOWN");
		assertThat(stateOf(json, "pending")).isEqualTo("PENDING");
		assertThat(stateOf(json, "paused")).as("inactive wins over a failing check and an open incident").isEqualTo("PAUSED");
	}

	@Test
	@DisplayName("precedence: DOWN beats DEGRADED, and a RESOLVED incident does not count")
	void precedenceAndResolvedIncidents() throws Exception {
		Site both = site("both");
		down(both, T);
		openIncident(both, IncidentType.HIGH_LATENCY, T);

		Site recovered = site("recovered");
		up(recovered, T, 100);
		resolvedIncident(recovered, IncidentType.DOWN, T.minusSeconds(3600), 600);
		resolvedIncident(recovered, IncidentType.HIGH_LATENCY, T.minusSeconds(7200), 600);

		String json = statusJson();

		assertThat(stateOf(json, "both")).isEqualTo("DOWN");
		assertThat(stateOf(json, "recovered")).isEqualTo("UP");
		assertThat(JsonPath.<List<?>>read(json, "$.sites[?(@.name == 'recovered')].openIncidents[*]")).isEmpty();
	}

	@Test
	@DisplayName("the last check is the newest by checked_at, not the one with the highest id")
	void lastCheckIsTheNewestByTime() throws Exception {
		Site site = site("out-of-order");
		up(site, T, 111); // inserted first, but it is the NEWER check
		down(site, T.minusSeconds(600)); // inserted last (higher id), older

		String json = statusJson();

		assertThat(stateOf(json, "out-of-order")).isEqualTo("UP");
		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(jsonPath("$.sites[0].lastCheck.up").value(true))
				.andExpect(jsonPath("$.sites[0].lastCheck.latencyMs").value(111))
				.andExpect(jsonPath("$.sites[0].lastCheck.checkedAt").value(T.toString()));
	}

	// ---- content ----

	@Test
	@DisplayName("each site shows its id, name, url, active flag, last check and open incidents with their start")
	void siteContent() throws Exception {
		Site site = site("full");
		Instant cert = NOW.plus(Duration.ofDays(9));
		checks.saveAndFlush(dev.josemariamerchan.controlweb.monitoring.domain.Check.up(site, T, 204, 87, cert));
		openIncident(site, IncidentType.SSL_EXPIRING, T.minusSeconds(300));
		openIncident(site, IncidentType.HIGH_LATENCY, T.minusSeconds(900));

		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sites[0].id").value(site.getId()))
				.andExpect(jsonPath("$.sites[0].name").value("full"))
				.andExpect(jsonPath("$.sites[0].url").value(site.getUrl()))
				.andExpect(jsonPath("$.sites[0].active").value(true))
				.andExpect(jsonPath("$.sites[0].status").value("DEGRADED"))
				.andExpect(jsonPath("$.sites[0].lastCheck.up").value(true))
				.andExpect(jsonPath("$.sites[0].lastCheck.statusCode").value(204))
				.andExpect(jsonPath("$.sites[0].lastCheck.latencyMs").value(87))
				.andExpect(jsonPath("$.sites[0].lastCheck.checkedAt").value(T.toString()))
				.andExpect(jsonPath("$.sites[0].lastCheck.certExpiresAt").value(cert.toString()))
				.andExpect(jsonPath("$.sites[0].openIncidents.length()").value(2))
				.andExpect(jsonPath("$.sites[0].openIncidents[0].type").value("HIGH_LATENCY"))
				.andExpect(jsonPath("$.sites[0].openIncidents[0].since").value(T.minusSeconds(900).toString()))
				.andExpect(jsonPath("$.sites[0].openIncidents[1].type").value("SSL_EXPIRING"));
	}

	@Test
	@DisplayName("a site without checks has no lastCheck and no open incidents")
	void pendingSiteHasNoLastCheck() throws Exception {
		site("brand-new");

		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(jsonPath("$.sites[0].status").value("PENDING"))
				.andExpect(jsonPath("$.sites[0].lastCheck").value(nullValue()))
				.andExpect(jsonPath("$.sites[0].openIncidents.length()").value(0));
	}

	@Test
	@DisplayName("a failed last check shows its status code, and a check without status code is null, not 0")
	void failedLastCheck() throws Exception {
		Site site = site("failing");
		checks.saveAndFlush(dev.josemariamerchan.controlweb.monitoring.domain.Check.down(site, T, null, null, "Connection failed"));

		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(jsonPath("$.sites[0].lastCheck.up").value(false))
				.andExpect(jsonPath("$.sites[0].lastCheck.statusCode").value(nullValue()))
				.andExpect(jsonPath("$.sites[0].lastCheck.latencyMs").value(nullValue()));
	}

	// ---- summary, order, empty ----

	@Test
	@DisplayName("the summary counts the sites of each state")
	void summary() throws Exception {
		up(site("a"), T, 100);
		up(site("b"), T, 100);
		Site degraded = site("c");
		up(degraded, T, 100);
		openIncidents(degraded);
		down(site("d"), T);
		site("e");
		site("f");
		site("g");
		site("h", false);

		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(jsonPath("$.summary.total").value(8))
				.andExpect(jsonPath("$.summary.up").value(2))
				.andExpect(jsonPath("$.summary.degraded").value(1))
				.andExpect(jsonPath("$.summary.down").value(1))
				.andExpect(jsonPath("$.summary.pending").value(3))
				.andExpect(jsonPath("$.summary.paused").value(1))
				.andExpect(jsonPath("$.sites.length()").value(8));
	}

	private void openIncidents(Site site) {
		openIncident(site, IncidentType.SSL_EXPIRING, T);
	}

	@Test
	@DisplayName("sites are ordered by name (ignoring case) and then by id, so the order is stable")
	void stableOrder() throws Exception {
		Site charlie = site("charlie");
		Site bravoUpper = site("Bravo");
		Site alpha = site("alpha");
		Site twinFirst = site("Same name");
		Site twinSecond = site("Same name");
		Site twinThird = site("Same name");

		String json = statusJson();
		List<Integer> ids = JsonPath.read(json, "$.sites[*].id");
		List<String> names = JsonPath.read(json, "$.sites[*].name");

		assertThat(names).containsExactly("alpha", "Bravo", "charlie", "Same name", "Same name", "Same name");
		assertThat(ids).containsExactly(alpha.getId().intValue(), bravoUpper.getId().intValue(), charlie.getId().intValue(),
				twinFirst.getId().intValue(), twinSecond.getId().intValue(), twinThird.getId().intValue());
		assertThat(JsonPath.<Object>read(statusJson(), "$.sites")).as("asking twice gives the same answer")
				.isEqualTo(JsonPath.<Object>read(json, "$.sites"));
	}

	@Test
	@DisplayName("no sites: an empty board with a zero summary")
	void emptyBoard() throws Exception {
		mvc.perform(get("/api/status").with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sites.length()").value(0))
				.andExpect(jsonPath("$.summary.total").value(0))
				.andExpect(jsonPath("$.summary.up").value(0));
	}

	@Test
	@DisplayName("generatedAt comes from the application's clock")
	void generatedAtUsesTheClock() throws Exception {
		clock.fix(NOW);

		mvc.perform(get("/api/status").with(TestTokens.bearer())).andExpect(jsonPath("$.generatedAt").value(NOW.toString()));
	}

	// ---- DTOs only, read only ----

	@Test
	@DisplayName("the response is made of DTOs: only the documented fields, no entity internals")
	void onlyDocumentedFields() throws Exception {
		Site site = site("shape");
		up(site, T, 100);
		openIncident(site, IncidentType.HIGH_LATENCY, T);

		String json = statusJson();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("generatedAt", "summary", "sites");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.summary"))
				.containsOnlyKeys("total", "up", "degraded", "down", "pending", "paused");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.sites[0]"))
				.containsOnlyKeys("id", "name", "url", "active", "status", "lastCheck", "openIncidents");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.sites[0].lastCheck"))
				.containsOnlyKeys("up", "statusCode", "latencyMs", "checkedAt", "certExpiresAt");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.sites[0].openIncidents[0]")).containsOnlyKeys("type", "since");
	}

	@Test
	@DisplayName("it requires a token, and only GET exists")
	void authentication() throws Exception {
		mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/status").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
	}

	// ---- no N+1 ----

	private int statementsForStatusWith(int siteCount) throws Exception {
		sites.deleteAll();
		for (int i = 0; i < siteCount; i++) {
			Site site = site("site-" + i);
			up(site, T.minusSeconds(60), 100);
			up(site, T, 100);
			down(site, T.minusSeconds(120));
			openIncident(site, IncidentType.HIGH_LATENCY, T);
			if (i % 2 == 0) {
				openIncident(site, IncidentType.SSL_EXPIRING, T);
			}
		}
		SqlStatementCounter.reset();
		mvc.perform(get("/api/status").with(TestTokens.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.sites.length()").value(siteCount));
		List<String> statements = SqlStatementCounter.statements();
		assertThat(statements).as("SQL issued for %d sites:%n%s", siteCount, String.join("\n---\n", statements)).isNotEmpty();
		return statements.size();
	}

	@Test
	@DisplayName("no N+1: 20 sites (each with checks and open incidents) cost the same few statements as 2 sites")
	void statusCostsAFixedNumberOfStatements() throws Exception {
		int withTwo = statementsForStatusWith(2);
		int withTwenty = statementsForStatusWith(20);

		assertThat(withTwenty).as("statements for 20 sites vs 2 sites").isEqualTo(withTwo);
		assertThat(withTwenty).as("sites + latest check per site + open incidents").isLessThanOrEqualTo(3);
	}
}
