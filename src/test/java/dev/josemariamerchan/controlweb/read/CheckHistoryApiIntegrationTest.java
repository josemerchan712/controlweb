package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.josemariamerchan.controlweb.SqlStatementCounter;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.site.domain.Site;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckHistoryApiIntegrationTest extends ReadApiTestSupport {

	private static final Instant BASE = NOW.minus(Duration.ofDays(1));

	/** {@code query} is "a=b&c=d", passed RAW (no URL decoding), so '+' in an offset stays a '+'. */
	private ResultActions history(long siteId, String query) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/sites/{id}/checks", siteId).with(TestTokens.bearer());
		if (!query.isEmpty()) {
			for (String pair : query.split("&")) {
				int equals = pair.indexOf('=');
				request.queryParam(pair.substring(0, equals), pair.substring(equals + 1));
			}
		}
		return mvc.perform(request);
	}

	private void assertProblem400(ResultActions result, String detailPart) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(containsString(detailPart)));
	}

	// ---- pagination and order ----

	@Test
	@DisplayName("default page: 50 newest checks first, with the totals")
	void defaultPage() throws Exception {
		Site site = site("history");
		seedChecks(site, BASE, 120);

		history(site.getId(), "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(50))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.totalElements").value(120))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items[0].checkedAt").value(BASE.plus(Duration.ofMinutes(120)).toString()))
				.andExpect(jsonPath("$.items[49].checkedAt").value(BASE.plus(Duration.ofMinutes(71)).toString()));
	}

	@Test
	@DisplayName("walking all the pages yields every check once, strictly newest to oldest")
	void pagesCoverEverythingInOrder() throws Exception {
		Site site = site("walk");
		seedChecks(site, BASE, 120);
		List<Integer> ids = new ArrayList<>();
		List<String> times = new ArrayList<>();

		for (int page = 0; page < 3; page++) {
			String json = history(site.getId(), "page=" + page + "&size=50").andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsString();
			ids.addAll(JsonPath.read(json, "$.items[*].id"));
			times.addAll(JsonPath.read(json, "$.items[*].checkedAt"));
		}

		assertThat(ids).hasSize(120).doesNotHaveDuplicates();
		assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(times).doesNotHaveDuplicates();
		history(site.getId(), "page=2&size=50").andExpect(jsonPath("$.items.length()").value(20));
		history(site.getId(), "page=3&size=50").andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalElements").value(120));
	}

	@Test
	@DisplayName("checks with the same timestamp are ordered by id, so pages never overlap or lose items")
	void tiesAreBrokenById() throws Exception {
		Site site = site("ties");
		for (int i = 0; i < 6; i++) {
			insertCheck(site, BASE, true, 200, 100);
		}

		List<Integer> first = JsonPath.read(history(site.getId(), "size=3&page=0").andReturn().getResponse().getContentAsString(), "$.items[*].id");
		List<Integer> second = JsonPath.read(history(site.getId(), "size=3&page=1").andReturn().getResponse().getContentAsString(), "$.items[*].id");

		assertThat(first).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(second).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(first.get(2)).isGreaterThan(second.get(0));
		assertThat(first).doesNotContainAnyElementsOf(second);
	}

	@Test
	@DisplayName("the size limit is 100: 100 is fine, 101 is a 400 ProblemDetail")
	void sizeLimit() throws Exception {
		Site site = site("limit");
		seedChecks(site, BASE, 120);

		history(site.getId(), "size=100").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(100));
		assertProblem400(history(site.getId(), "size=101"), "size must be between 1 and 100");
	}

	@ParameterizedTest(name = "query \"{0}\" is a 400, never a 500")
	@ValueSource(strings = { "size=0", "size=-1", "size=abc", "size=1.5", "size=99999999999999", "page=-1", "page=abc", "page=2147483648",
			"from=yesterday", "to=2026-03-01", "from=2026-03-01T00:00:00", "from=1700000000", "from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z",
			"from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:01Z", "from='; drop table checks;--" })
	void invalidParametersAre400(String query) throws Exception {
		Site site = site("invalid");

		history(site.getId(), query)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").isNotEmpty());
	}

	@Test
	@DisplayName("a non-numeric site id is a 400")
	void badSiteId() throws Exception {
		mvc.perform(get("/api/sites/abc/checks").with(TestTokens.bearer())).andExpect(status().isBadRequest());
	}

	// ---- from / to ----

	@Test
	@DisplayName("from and to are inclusive and optional")
	void dateFilters() throws Exception {
		Site site = site("filters");
		for (int hour = 0; hour < 10; hour++) {
			up(site, BASE.plus(Duration.ofHours(hour)), 100 + hour); // hours 0..9
		}
		String from = BASE.plus(Duration.ofHours(3)).toString();
		String to = BASE.plus(Duration.ofHours(6)).toString();

		history(site.getId(), "from=" + from + "&to=" + to).andExpect(jsonPath("$.totalElements").value(4))
				.andExpect(jsonPath("$.items[0].latencyMs").value(106)).andExpect(jsonPath("$.items[3].latencyMs").value(103));
		history(site.getId(), "from=" + from).andExpect(jsonPath("$.totalElements").value(7));
		history(site.getId(), "to=" + to).andExpect(jsonPath("$.totalElements").value(7));
		history(site.getId(), "from=" + from + "&to=" + from).andExpect(jsonPath("$.totalElements").value(1));
		history(site.getId(), "").andExpect(jsonPath("$.totalElements").value(10));
		history(site.getId(), "from=" + BASE.plus(Duration.ofDays(1)) + "&to=" + BASE.plus(Duration.ofDays(2)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("offsets are understood: the same instant written with +01:00 selects the same checks")
	void timeZoneOffsets() throws Exception {
		Site site = site("zones");
		up(site, Instant.parse("2026-02-28T10:00:00Z"), 100);
		up(site, Instant.parse("2026-02-28T11:00:00Z"), 101);

		history(site.getId(), "from=2026-02-28T12:00:00+01:00").andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].latencyMs").value(101));
	}

	@Test
	@DisplayName("a range of exactly 90 days is accepted, one second more is a 400")
	void ninetyDayLimit() throws Exception {
		Site site = site("range");
		up(site, Instant.parse("2026-02-01T00:00:00Z"), 100);

		history(site.getId(), "from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:00Z").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
		assertProblem400(history(site.getId(), "from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:01Z"),
				"cannot exceed 90 days");
		history(site.getId(), "from=2020-01-01T00:00:00Z").andExpect(status().isOk());
	}

	@Test
	@DisplayName("from after to is a 400 that says so")
	void fromAfterTo() throws Exception {
		Site site = site("reversed");

		assertProblem400(history(site.getId(), "from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z"), "from must not be after to");
	}

	// ---- content, isolation, errors ----

	@Test
	@DisplayName("only the site's own checks are listed, with the documented fields and nothing else")
	void contentAndIsolation() throws Exception {
		Site mine = site("mine");
		Site other = site("other");
		Instant cert = NOW.plus(Duration.ofDays(30));
		checks.saveAndFlush(Check.up(mine, BASE, 201, 77, cert));
		checks.saveAndFlush(Check.down(mine, BASE.plusSeconds(60), 503, 4000, "HTTP 503"));
		up(other, BASE, 1);

		String json = history(mine.getId(), "").andExpect(jsonPath("$.totalElements").value(2)).andReturn().getResponse()
				.getContentAsString();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items", "page", "size", "totalElements", "totalPages");
		assertThat(JsonPath.<Map<String, Object>>read(json, "$.items[0]"))
				.containsOnlyKeys("id", "checkedAt", "up", "statusCode", "latencyMs", "errorMessage", "certExpiresAt");
		history(mine.getId(), "")
				.andExpect(jsonPath("$.items[0].up").value(false))
				.andExpect(jsonPath("$.items[0].statusCode").value(503))
				.andExpect(jsonPath("$.items[0].errorMessage").value("HTTP 503"))
				.andExpect(jsonPath("$.items[1].up").value(true))
				.andExpect(jsonPath("$.items[1].certExpiresAt").value(cert.toString()))
				.andExpect(jsonPath("$.items[1].errorMessage").doesNotExist());
	}

	@Test
	@DisplayName("an unknown site is a 404 ProblemDetail; no token is a 401; only GET exists")
	void errorsAndAuthentication() throws Exception {
		history(999_999, "").andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404));
		mvc.perform(get("/api/sites/1/checks")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/sites/1/checks").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
	}

	@Test
	@DisplayName("a page costs at most two statements (the rows and the total), whatever its size")
	void pageCostsTwoStatements() throws Exception {
		Site site = site("cost");
		seedChecks(site, BASE, 120);

		SqlStatementCounter.reset();
		history(site.getId(), "size=100").andExpect(status().isOk());

		assertThat(SqlStatementCounter.statements()).as(String.join("\n---\n", SqlStatementCounter.statements()))
				.hasSizeLessThanOrEqualTo(3); // site exists + rows + count
	}
}
