package dev.josemariamerchan.controlweb.publicstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import dev.josemariamerchan.controlweb.SqlStatementCounter;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.read.ReadApiTestSupport;
import dev.josemariamerchan.controlweb.site.domain.Site;

/**
 * The public endpoint is the only API anyone on the Internet can call, so these tests are about what it must NOT
 * do: leak data, be influenced by private sites, or hammer the database. Every request here carries NO token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublicStatusIntegrationTest extends ReadApiTestSupport {

	private static final Instant T = NOW.minus(Duration.ofHours(1));
	private static final String PORTFOLIO = "https://portfolio.test";

	@Autowired
	private PublicStatusCache cache;

	@BeforeEach
	void freshState() {
		clock.fix(NOW);
		cache.invalidate();
	}

	@AfterEach
	void forgetCachedAnswer() {
		cache.invalidate();
	}

	private Site publicSite(String name) {
		return site(name, true, true);
	}

	/** The answer as the world sees it right now (the cache is emptied first, so it is read from the database). */
	private String freshBody() throws Exception {
		cache.invalidate();
		return mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static List<String> names(String json) {
		return JsonPath.read(json, "$.sites[*].name");
	}

	private static String stateOf(String json, String name) {
		List<String> states = JsonPath.read(json, "$.sites[?(@.name == '" + name + "')].status");
		assertThat(states).as("public site %s", name).hasSize(1);
		return states.get(0);
	}

	// ---- no token, GET only ----

	@Test
	@DisplayName("it answers 200 without any token")
	void noTokenNeeded() throws Exception {
		mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@ParameterizedTest(name = "{0} is a 405 problem+json, not an error page and not data")
	@ValueSource(strings = { "POST", "PUT", "DELETE", "PATCH" })
	void otherMethodsAre405(String method) throws Exception {
		var request = switch (method) {
			case "POST" -> post("/api/public/status");
			case "PUT" -> put("/api/public/status");
			case "DELETE" -> delete("/api/public/status");
			default -> patch("/api/public/status");
		};
		publicSite("visible");

		mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(405))
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(jsonPath("$.sites").doesNotExist());
	}

	@ParameterizedTest(name = "{0} is NOT public: 401")
	@ValueSource(strings = { "/api/public/other", "/api/public/", "/api/public", "/api/public/status/extra", "/api/public/status/",
			"/api/public/sites", "/api/public/status.json" })
	void nothingElseUnderPublicIsOpen(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest(name = "a stale or garbage Authorization header ({0}) does not break the public page")
	@ValueSource(strings = { "Bearer garbage", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.invalid", "Basic dXNlcjpwYXNz", "Bearer " })
	void authorizationHeaderIsIgnoredOnThePublicPage(String header) throws Exception {
		publicSite("visible");

		mvc.perform(get("/api/public/status").header("Authorization", header))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sites.length()").value(1));
	}

	// ---- the whitelist ----

	@Test
	@DisplayName("a trailing slash never gives the data to anyone who would not get it otherwise: it is not a 200")
	void trailingSlashIsNotAnAlias() throws Exception {
		publicSite("visible");

		int status = mvc.perform(get("/api/public/status/")).andReturn().getResponse().getStatus();

		assertThat(status).isIn(401, 404);
	}

	@Test
	@DisplayName("empty list and global UNKNOWN (with a 200) when there are no public sites")
	void emptyWhenNoPublicSites() throws Exception {
		site("private only", true, false);

		mvc.perform(get("/api/public/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.generatedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.sites.length()").value(0));
	}

	@Test
	@DisplayName("whitelist: the JSON has EXACTLY these keys, so adding a field by mistake breaks the build")
	void exactKeys() throws Exception {
		Site site = publicSite("keys");
		up(site, T, 120);
		openIncident(site, IncidentType.HIGH_LATENCY, T);

		String json = freshBody();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("status", "generatedAt", "sites");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.sites[0]")).containsOnlyKeys("name", "status", "uptime30d");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.sites[0]")).hasSize(3);
	}

	@Test
	@DisplayName("nothing identifying or internal reaches the body: no URL, host, id, latency, incident text, error text or certificate date")
	void bodyDoesNotLeak() throws Exception {
		Site site = site("Portfolio Web", "https://very-secret-host.internal.example.org:8443/health?token=QUERY-SECRET-123", true, true);
		checks.saveAndFlush(Check.up(site, T.minusSeconds(60), 200, 4321, NOW.plus(Duration.ofDays(9))));
		checks.saveAndFlush(Check.down(site, T, 503, 4321, "CHECK-ERROR-SECRET-789 while calling https://very-secret-host.internal.example.org/x"));
		incidents.saveAndFlush(Incident.open(site, IncidentType.DOWN, T, "INCIDENT-SECRET-TEXT-456 on very-secret-host"));
		incidents.saveAndFlush(Incident.open(site, IncidentType.SSL_EXPIRING, T, "certificate expires on 2026-03-10"));
		Site privateSite = site("Private Admin Panel", "https://admin.corp.internal.example.net/login", true, false);
		up(privateSite, T, 77);

		String body = freshBody();
		String lower = body.toLowerCase(Locale.ROOT);

		assertThat(body).contains("Portfolio Web");
		for (String forbidden : List.of("very-secret-host", "internal.example", "8443", "/health", "token=", "QUERY-SECRET", "https://",
				"http://", "INCIDENT-SECRET", "CHECK-ERROR-SECRET", "4321", "503", "certificate", "2026-03-10", "Private Admin",
				"admin.corp", "login", "77")) {
			assertThat(lower).as("the public body must not contain '%s':%n%s", forbidden, body).doesNotContain(forbidden.toLowerCase(Locale.ROOT));
		}
		for (String forbiddenKey : List.of("\"id\"", "siteId", "incident", "url", "latency", "statusCode", "details", "error", "cert",
				"checkedAt", "since", "active", "public")) {
			assertThat(body).as("no key like '%s'", forbiddenKey).doesNotContain(forbiddenKey);
		}
		assertThat(body).doesNotContain("\"" + site.getId() + "\"");
	}

	// ---- who is shown ----

	@Test
	@DisplayName("only sites that are public AND active are listed")
	void visibility() throws Exception {
		Site shown = publicSite("shown");
		up(shown, T, 100);
		up(site("public but paused", false, true), T, 100);
		up(site("private and active", true, false), T, 100);
		up(site("private and paused", false, false), T, 100);

		assertThat(names(freshBody())).containsExactly("shown");
	}

	@Test
	@DisplayName("sites are listed by name (ignoring case) then by id: a stable order")
	void stableOrder() throws Exception {
		publicSite("charlie");
		publicSite("Bravo");
		publicSite("alpha");
		Site twinDown = publicSite("Twin");
		down(twinDown, T);
		Site twinUp = publicSite("Twin");
		up(twinUp, T, 100);

		String json = freshBody();

		assertThat(names(json)).containsExactly("alpha", "Bravo", "charlie", "Twin", "Twin");
		assertThat(JsonPath.<List<String>>read(json, "$.sites[3:5].status")).as("same name: the older site first").containsExactly("DOWN", "UP");
	}

	// ---- states ----

	@Test
	@DisplayName("public states: UP, DEGRADED, DOWN (incident or failed check) and UNKNOWN (never checked); paused never shown")
	void publicStates() throws Exception {
		up(publicSite("healthy"), T, 100);
		Site slow = publicSite("slow");
		up(slow, T, 100);
		openIncident(slow, IncidentType.HIGH_LATENCY, T);
		Site expiring = publicSite("expiring");
		up(expiring, T, 100);
		openIncident(expiring, IncidentType.SSL_EXPIRING, T);
		Site downByIncident = publicSite("down by incident");
		up(downByIncident, T, 100);
		openIncident(downByIncident, IncidentType.DOWN, T);
		down(publicSite("down by check"), T);
		publicSite("brand new");
		Site recovered = publicSite("recovered");
		up(recovered, T, 100);
		resolvedIncident(recovered, IncidentType.DOWN, T.minusSeconds(3600), 600);

		String json = freshBody();

		assertThat(stateOf(json, "healthy")).isEqualTo("UP");
		assertThat(stateOf(json, "slow")).isEqualTo("DEGRADED");
		assertThat(stateOf(json, "expiring")).isEqualTo("DEGRADED");
		assertThat(stateOf(json, "down by incident")).isEqualTo("DOWN");
		assertThat(stateOf(json, "down by check")).isEqualTo("DOWN");
		assertThat(stateOf(json, "brand new")).as("PENDING collapses to UNKNOWN").isEqualTo("UNKNOWN");
		assertThat(stateOf(json, "recovered")).as("a resolved incident does not count").isEqualTo("UP");
		assertThat(json).doesNotContain("PENDING").doesNotContain("PAUSED");
	}

	@Test
	@DisplayName("the global state is computed from the public sites: UP, partial outage = DEGRADED, all down = DOWN, nothing known = UNKNOWN")
	void globalState() throws Exception {
		Site a = publicSite("a");
		Site b = publicSite("b");
		up(a, T, 100);
		up(b, T, 100);
		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.status").value("UP"));

		down(b, T.plusSeconds(60));
		cache.invalidate();
		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.status").value("DEGRADED"));

		down(a, T.plusSeconds(60));
		cache.invalidate();
		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.status").value("DOWN"));

		sites.deleteAll();
		publicSite("only new");
		cache.invalidate();
		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.status").value("UNKNOWN"));
	}

	// ---- uptime ----

	@Test
	@DisplayName("uptime30d: successful / all checks of the last 30 days, two decimals; null with no checks, 0 when all failed")
	void uptime() throws Exception {
		Site mixed = publicSite("mixed");
		up(mixed, NOW.minus(Duration.ofDays(1)), 100);
		up(mixed, NOW.minus(Duration.ofDays(2)), 100);
		down(mixed, NOW.minus(Duration.ofDays(3)));
		Site allDown = publicSite("all down");
		down(allDown, T);
		publicSite("no checks");
		Site perfect = publicSite("perfect");
		up(perfect, T, 100);

		String json = freshBody();

		assertThat(JsonPath.<List<Number>>read(json, "$.sites[?(@.name == 'mixed')].uptime30d").get(0).doubleValue()).isEqualTo(66.67);
		assertThat(JsonPath.<List<Number>>read(json, "$.sites[?(@.name == 'all down')].uptime30d").get(0).doubleValue()).isEqualTo(0.0);
		assertThat(JsonPath.<List<Number>>read(json, "$.sites[?(@.name == 'perfect')].uptime30d").get(0).doubleValue()).isEqualTo(100.0);
		assertThat(json).as("a site without checks has an explicit null, never 100").contains("\"uptime30d\":null");
	}

	@Test
	@DisplayName("the 30-day window is closed at both ends: exactly 30 days old counts, one second older and the future do not")
	void uptimeWindowBoundaries() throws Exception {
		Site site = publicSite("window");
		up(site, NOW.minus(Duration.ofDays(30)), 100); // inside
		down(site, NOW.minus(Duration.ofDays(30)).minusSeconds(1)); // outside
		down(site, NOW.plusSeconds(1)); // future: outside
		up(site, NOW, 100); // inside

		mvc.perform(get("/api/public/status")).andExpect(status().isOk());
		String json = freshBody();

		assertThat(JsonPath.<Number>read(json, "$.sites[0].uptime30d").doubleValue()).isEqualTo(100.0);
	}

	// ---- private sites change nothing ----

	@Test
	@DisplayName("a private site going DOWN changes NOTHING in the response: it is byte-for-byte the same")
	void privateSiteDownChangesNothing() throws Exception {
		up(publicSite("public one"), T, 100);
		up(publicSite("public two"), T, 100);
		Site secret = site("secret service", true, false);
		up(secret, T.minusSeconds(60), 100);

		String before = freshBody();

		down(secret, T);
		down(secret, T.plusSeconds(60));
		openIncident(secret, IncidentType.DOWN, T);
		openIncident(secret, IncidentType.HIGH_LATENCY, T);
		String after = freshBody();

		assertThat(after).isEqualTo(before);
		assertThat(JsonPath.<String>read(after, "$.status")).isEqualTo("UP");
	}

	@Test
	@DisplayName("with no public sites a failing private site leaves the answer at UNKNOWN / empty")
	void privateOutageWithNoPublicSites() throws Exception {
		Site secret = site("secret", true, false);
		String before = freshBody();

		down(secret, T);
		openIncident(secret, IncidentType.DOWN, T);

		assertThat(freshBody()).isEqualTo(before).contains("\"status\":\"UNKNOWN\"").contains("\"sites\":[]");
	}

	@Test
	@DisplayName("making a site private (or pausing it) removes it from the public page")
	void changingVisibilityTakesEffect() throws Exception {
		Site site = publicSite("toggled");
		up(site, T, 100);
		assertThat(names(freshBody())).containsExactly("toggled");

		site.update("toggled", site.getUrl(), 60, true, false, NOW);
		sites.saveAndFlush(site);

		assertThat(names(freshBody())).isEmpty();
	}

	// ---- headers ----

	@Test
	@DisplayName("headers: Cache-Control public max-age=30, nosniff, and no cookie")
	void headers() throws Exception {
		up(publicSite("headers"), T, 100);

		mvc.perform(get("/api/public/status"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "public, max-age=30"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(header().doesNotExist("Pragma"))
				.andExpect(header().doesNotExist("Expires"));
	}

	@Test
	@DisplayName("error responses are never publicly cacheable and keep nosniff")
	void errorResponsesAreNotCacheable() throws Exception {
		mvc.perform(post("/api/public/status")).andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(containsString("public"))))
				.andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().doesNotExist("Set-Cookie"));
		mvc.perform(get("/api/public/other")).andExpect(status().isUnauthorized())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(containsString("public"))));
	}

	@Test
	@DisplayName("a request carrying a cookie gets none back, and the answer is the same for everyone")
	void sameAnswerForEveryone() throws Exception {
		up(publicSite("everyone"), T, 100);
		String anonymous = mvc.perform(get("/api/public/status")).andReturn().getResponse().getContentAsString();

		MvcResult withCookie = mvc.perform(get("/api/public/status").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "abc"))
				.header("User-Agent", "something else")).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();

		assertThat(withCookie.getResponse().getContentAsString()).isEqualTo(anonymous);
	}

	// ---- CORS ----

	@Test
	@DisplayName("CORS: the configured portfolio origin gets the headers (its exact origin, no credentials, never a wildcard)")
	void allowedOrigin() throws Exception {
		up(publicSite("cors"), T, 100);

		mvc.perform(get("/api/public/status").header("Origin", PORTFOLIO))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", PORTFOLIO))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
				.andExpect(header().string("Vary", containsString("Origin")));
	}

	@ParameterizedTest(name = "origin {0} is refused")
	@ValueSource(strings = { "https://evil.example.com", "http://localhost:5173", "https://portfolio.test.evil.com", "http://portfolio.test",
			"https://portfolio.test:8443", "null" })
	void otherOrigins(String origin) throws Exception {
		mvc.perform(get("/api/public/status").header("Origin", origin))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	@DisplayName("CORS preflight: GET is allowed for the portfolio; POST and extra headers are not; other origins are refused")
	void preflight() throws Exception {
		mvc.perform(options("/api/public/status").header("Origin", PORTFOLIO).header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", PORTFOLIO))
				.andExpect(header().string("Access-Control-Allow-Methods", "GET"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
		for (String method : List.of("POST", "PUT", "DELETE")) {
			mvc.perform(options("/api/public/status").header("Origin", PORTFOLIO).header("Access-Control-Request-Method", method))
					.andExpect(status().isForbidden());
		}
		mvc.perform(options("/api/public/status").header("Origin", PORTFOLIO).header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "authorization")).andExpect(status().isForbidden());
		mvc.perform(options("/api/public/status").header("Origin", "https://evil.example.com").header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("the two CORS policies are separate: the portfolio cannot call the private API, nor the dashboard the public one")
	void separatePolicies() throws Exception {
		mvc.perform(options("/api/sites").header("Origin", PORTFOLIO).header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "authorization")).andExpect(status().isForbidden());
		mvc.perform(options("/api/sites").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "authorization")).andExpect(status().isOk());
		mvc.perform(get("/api/public/status").header("Origin", "http://localhost:5173")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("without an Origin header (a plain HTTP client) there are no CORS headers and the answer is the same")
	void noOrigin() throws Exception {
		mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	// ---- protecting the database ----

	private List<String> statementsFor(Runnable action) {
		SqlStatementCounter.reset();
		action.run();
		return SqlStatementCounter.statements();
	}

	private void get50Times(int times) {
		for (int i = 0; i < times; i++) {
			try {
				mvc.perform(get("/api/public/status")).andExpect(status().isOk());
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	@Test
	@DisplayName("N requests inside the TTL cost the same SQL as ONE request; and only public data is asked for")
	void manyRequestsCostOneRead() throws Exception {
		for (int i = 0; i < 5; i++) {
			Site site = publicSite("site-" + i);
			up(site, T, 100);
			openIncident(site, IncidentType.HIGH_LATENCY, T);
		}
		site("private", true, false);

		cache.invalidate();
		List<String> one = statementsFor(() -> get50Times(1));
		cache.invalidate();
		List<String> many = statementsFor(() -> get50Times(50));

		assertThat(one).as("one request:%n%s", String.join("\n---\n", one)).isNotEmpty().hasSizeLessThanOrEqualTo(4);
		assertThat(many).as("50 requests").hasSameSizeAs(one);
		assertThat(one.get(0).toLowerCase(Locale.ROOT)).as("the very first query already restricts to public sites")
				.contains("public_visible").contains("active");
		assertThat(one).allSatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).startsWith("select"));
	}

	@Test
	@DisplayName("inside the TTL the data does not change and costs nothing; after the TTL it is read again")
	void ttlAndReload() throws Exception {
		Site site = publicSite("ttl");
		up(site, T, 100);
		String first = mvc.perform(get("/api/public/status")).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("UP");
		down(site, NOW.minusSeconds(30)); // the site goes down in the database...

		clock.fix(NOW.plusSeconds(14));
		List<String> inside = statementsFor(() -> get50Times(10));
		String stillCached = mvc.perform(get("/api/public/status")).andReturn().getResponse().getContentAsString();

		assertThat(inside).as("nothing is read inside the TTL").isEmpty();
		assertThat(stillCached).as("...but the cached answer still says UP").isEqualTo(first);

		clock.fix(NOW.plusSeconds(15));
		List<String> reload = new java.util.ArrayList<>(statementsFor(() -> get50Times(1)));
		String reloaded = mvc.perform(get("/api/public/status")).andReturn().getResponse().getContentAsString();

		assertThat(reload).as("after the TTL it is read again").isNotEmpty();
		assertThat(JsonPath.<String>read(reloaded, "$.status")).isEqualTo("DOWN");
		assertThat(JsonPath.<String>read(reloaded, "$.generatedAt")).isEqualTo(NOW.plusSeconds(15).toString());
		assertThat(statementsFor(() -> get50Times(5))).as("and cached again").isEmpty();
	}

	@Test
	@DisplayName("generatedAt is when the data was read, not when it was served")
	void generatedAtIsTheReadTime() throws Exception {
		publicSite("when");
		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.generatedAt").value(NOW.toString()));

		clock.fix(NOW.plusSeconds(10));

		mvc.perform(get("/api/public/status")).andExpect(jsonPath("$.generatedAt").value(NOW.toString()));
	}

	@Test
	@DisplayName("the response is valid JSON with the status field first-level and uptime30d null-safe")
	void nullUptimeIsExplicit() throws Exception {
		publicSite("fresh");

		mvc.perform(get("/api/public/status"))
				.andExpect(jsonPath("$.sites[0].uptime30d").value(nullValue()))
				.andExpect(jsonPath("$.sites[0].status").value("UNKNOWN"));
	}
}
