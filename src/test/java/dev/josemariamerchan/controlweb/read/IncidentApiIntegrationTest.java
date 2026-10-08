package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import dev.josemariamerchan.controlweb.SqlStatementCounter;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.site.domain.Site;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IncidentApiIntegrationTest extends ReadApiTestSupport {

	private static final Instant T = NOW.minus(Duration.ofDays(1));

	private ResultActions list(String query) throws Exception {
		var request = get("/api/incidents").with(TestTokens.bearer());
		if (!query.isEmpty()) {
			for (String pair : query.split("&")) {
				int equals = pair.indexOf('=');
				request.queryParam(pair.substring(0, equals), pair.substring(equals + 1));
			}
		}
		return mvc.perform(request);
	}

	private List<Integer> ids(String query) throws Exception {
		return JsonPath.read(list(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
	}

	// ---- listing ----

	@Test
	@DisplayName("incidents come newest first (opened_at DESC), with their site's name")
	void orderedNewestFirst() throws Exception {
		Site blog = site("Blog");
		Site shop = site("Shop");
		Incident oldest = resolvedIncident(blog, IncidentType.DOWN, T, 600);
		Incident middle = openIncident(shop, IncidentType.HIGH_LATENCY, T.plusSeconds(3600));
		Incident newest = resolvedIncident(blog, IncidentType.SSL_EXPIRING, T.plusSeconds(7200), 60);

		assertThat(ids("")).containsExactly(newest.getId().intValue(), middle.getId().intValue(), oldest.getId().intValue());
		list("").andExpect(jsonPath("$.items[0].siteName").value("Blog")).andExpect(jsonPath("$.items[1].siteName").value("Shop"))
				.andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	@DisplayName("incidents opened at the same instant are ordered by id (stable)")
	void ties() throws Exception {
		Site site = site("Ties");
		Incident first = resolvedIncident(site, IncidentType.DOWN, T, 10);
		Incident second = resolvedIncident(site, IncidentType.HIGH_LATENCY, T, 10);
		Incident third = resolvedIncident(site, IncidentType.SSL_EXPIRING, T, 10);

		assertThat(ids("")).containsExactly(third.getId().intValue(), second.getId().intValue(), first.getId().intValue());
	}

	@Test
	@DisplayName("status=open and status=resolved filter (case-insensitive); no status returns both")
	void statusFilter() throws Exception {
		Site site = site("Filters");
		Incident resolved = resolvedIncident(site, IncidentType.DOWN, T, 300);
		Incident open = openIncident(site, IncidentType.DOWN, T.plusSeconds(1000));

		assertThat(ids("status=open")).containsExactly(open.getId().intValue());
		assertThat(ids("status=OPEN")).containsExactly(open.getId().intValue());
		assertThat(ids("status=resolved")).containsExactly(resolved.getId().intValue());
		assertThat(ids("status=Resolved")).containsExactly(resolved.getId().intValue());
		assertThat(ids("")).containsExactly(open.getId().intValue(), resolved.getId().intValue());
		list("status=open").andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("siteId filters by site, alone or with status")
	void siteFilter() throws Exception {
		Site blog = site("Blog");
		Site shop = site("Shop");
		Incident blogOpen = openIncident(blog, IncidentType.DOWN, T);
		Incident blogResolved = resolvedIncident(blog, IncidentType.HIGH_LATENCY, T.minusSeconds(5000), 100);
		Incident shopOpen = openIncident(shop, IncidentType.DOWN, T.plusSeconds(10));

		assertThat(ids("siteId=" + blog.getId())).containsExactly(blogOpen.getId().intValue(), blogResolved.getId().intValue());
		assertThat(ids("siteId=" + shop.getId())).containsExactly(shopOpen.getId().intValue());
		assertThat(ids("siteId=" + blog.getId() + "&status=resolved")).containsExactly(blogResolved.getId().intValue());
		assertThat(ids("siteId=" + shop.getId() + "&status=resolved")).isEmpty();
	}

	@Test
	@DisplayName("an unknown siteId is a 404 ProblemDetail, not an empty list")
	void unknownSiteFilter() throws Exception {
		list("siteId=999999").andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("pagination: default size 50, size limit 100, totals, and pages cover everything exactly once")
	void pagination() throws Exception {
		Site site = site("Many");
		for (int i = 0; i < 120; i++) {
			resolvedIncident(site, IncidentType.values()[i % 3], T.plusSeconds(60L * i), 30);
		}

		list("").andExpect(jsonPath("$.items.length()").value(50)).andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.totalElements").value(120)).andExpect(jsonPath("$.totalPages").value(3));
		list("size=100").andExpect(jsonPath("$.items.length()").value(100));
		list("size=101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("size must be between 1 and 100"));
		list("page=2&size=50").andExpect(jsonPath("$.items.length()").value(20));
		List<Integer> all = new java.util.ArrayList<>();
		for (int page = 0; page < 3; page++) {
			all.addAll(ids("page=" + page + "&size=50"));
		}
		assertThat(all).hasSize(120).doesNotHaveDuplicates();
		list("page=0&size=50").andExpect(jsonPath("$.items[0].openedAt").value(T.plusSeconds(60L * 119).toString()));
	}

	@ParameterizedTest(name = "query \"{0}\" is a 400, never a 500")
	@ValueSource(strings = { "status=closed", "status=1", "status=open;resolved", "size=0", "size=-1", "size=abc", "page=-1", "page=x",
			"siteId=abc", "siteId=1.5", "siteId=99999999999999999999" })
	void invalidParameters(String query) throws Exception {
		list(query).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	// ---- content ----

	@Test
	@DisplayName("a resolved incident shows its duration in seconds; an open one is ongoing, with no duration")
	void durationOrOngoing() throws Exception {
		Site site = site("Durations");
		resolvedIncident(site, IncidentType.DOWN, T, 754);
		openIncident(site, IncidentType.HIGH_LATENCY, T.plusSeconds(100));

		list("").andExpect(jsonPath("$.items[0].status").value("OPEN")).andExpect(jsonPath("$.items[0].ongoing").value(true))
				.andExpect(jsonPath("$.items[0].durationSeconds").value(nullValue()))
				.andExpect(jsonPath("$.items[0].resolvedAt").value(nullValue()))
				.andExpect(jsonPath("$.items[1].status").value("RESOLVED")).andExpect(jsonPath("$.items[1].ongoing").value(false))
				.andExpect(jsonPath("$.items[1].durationSeconds").value(754))
				.andExpect(jsonPath("$.items[1].resolvedAt").value(T.plusSeconds(754).toString()));
	}

	@Test
	@DisplayName("each incident has exactly the documented fields (no entity internals, no site object)")
	void fieldsOfAnIncident() throws Exception {
		Site site = site("Fields");
		Incident incident = resolvedIncident(site, IncidentType.SSL_EXPIRING, T, 60);

		String json = list("").andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$.items[0]")).containsOnlyKeys("id", "siteId", "siteName", "type", "status",
				"openedAt", "resolvedAt", "ongoing", "durationSeconds", "details");
		list("").andExpect(jsonPath("$.items[0].id").value(incident.getId())).andExpect(jsonPath("$.items[0].siteId").value(site.getId()))
				.andExpect(jsonPath("$.items[0].type").value("SSL_EXPIRING"))
				.andExpect(jsonPath("$.items[0].details").value("details of SSL_EXPIRING | Closed: Site recovered"));
	}

	// ---- one incident ----

	@Test
	@DisplayName("GET /api/incidents/{id}: the incident with its site name and duration")
	void incidentDetail() throws Exception {
		Site site = site("Detail");
		Incident incident = resolvedIncident(site, IncidentType.DOWN, T, 120);
		Incident open = openIncident(site, IncidentType.HIGH_LATENCY, T);

		mvc.perform(get("/api/incidents/{id}", incident.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(incident.getId())).andExpect(jsonPath("$.siteName").value("Detail"))
				.andExpect(jsonPath("$.durationSeconds").value(120)).andExpect(jsonPath("$.ongoing").value(false));
		mvc.perform(get("/api/incidents/{id}", open.getId()).with(TestTokens.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.ongoing").value(true)).andExpect(jsonPath("$.durationSeconds").value(nullValue()));
	}

	@Test
	@DisplayName("an unknown incident is a 404 ProblemDetail; a non-numeric id is a 400")
	void incidentErrors() throws Exception {
		mvc.perform(get("/api/incidents/999999").with(TestTokens.bearer())).andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("$.status").value(404));
		mvc.perform(get("/api/incidents/abc").with(TestTokens.bearer())).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("both endpoints require a token and are read-only")
	void authenticationAndMethods() throws Exception {
		mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/incidents/1")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/incidents").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
		mvc.perform(delete("/api/incidents/1").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
	}

	// ---- cost ----

	@Test
	@DisplayName("a page of 20 incidents from 20 different sites costs two statements at most (rows + total): no query per row")
	void pageCostIsFixed() throws Exception {
		for (int i = 0; i < 20; i++) {
			openIncident(site("site-" + i), IncidentType.DOWN, T.plusSeconds(i));
		}

		SqlStatementCounter.reset();
		list("size=20").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
				.andExpect(jsonPath("$.items[0].siteName").isNotEmpty());

		assertThat(SqlStatementCounter.statements()).as(String.join("\n---\n", SqlStatementCounter.statements()))
				.hasSizeLessThanOrEqualTo(2);
	}

	@Test
	@DisplayName("one incident costs a single statement, site included")
	void detailCostIsOne() throws Exception {
		Incident incident = openIncident(site("One"), IncidentType.DOWN, T);

		SqlStatementCounter.reset();
		mvc.perform(get("/api/incidents/{id}", incident.getId()).with(TestTokens.bearer())).andExpect(status().isOk());

		assertThat(SqlStatementCounter.statements()).as(String.join("\n---\n", SqlStatementCounter.statements())).hasSize(1);
	}
}
