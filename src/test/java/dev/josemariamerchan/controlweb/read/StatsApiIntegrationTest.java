package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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

import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.site.domain.Site;

/** Statistics, with the clock fixed at {@link #NOW}; seeded values are chosen so every expected number is known. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatsApiIntegrationTest extends ReadApiTestSupport {

	@BeforeEach
	void fixTheClock() {
		clock.fix(NOW);
	}

	private ResultActions stats(long siteId, String period) throws Exception {
		return mvc.perform(get("/api/sites/{id}/stats", siteId).queryParam("period", period).with(TestTokens.bearer()));
	}

	private ResultActions stats(long siteId) throws Exception {
		return mvc.perform(get("/api/sites/{id}/stats", siteId).with(TestTokens.bearer()));
	}

	private static Instant hoursAgo(long hours) {
		return NOW.minus(Duration.ofHours(hours));
	}

	// ---- uptime ----

	@Test
	@DisplayName("uptime = successful checks / ALL checks of the period (failed checks count against it)")
	void uptimeCountsFailedChecks() throws Exception {
		Site site = site("uptime");
		up(site, hoursAgo(5), 100);
		up(site, hoursAgo(4), 100);
		up(site, hoursAgo(3), 100);
		down(site, hoursAgo(2));

		stats(site.getId(), "24h")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalChecks").value(4))
				.andExpect(jsonPath("$.successfulChecks").value(3))
				.andExpect(jsonPath("$.uptimePercent").value(75.0));
	}

	@Test
	@DisplayName("uptime is rounded to two decimals")
	void uptimeRounding() throws Exception {
		Site twoThirds = site("two-thirds");
		up(twoThirds, hoursAgo(3), 100);
		up(twoThirds, hoursAgo(2), 100);
		down(twoThirds, hoursAgo(1));
		Site oneThird = site("one-third");
		up(oneThird, hoursAgo(3), 100);
		down(oneThird, hoursAgo(2));
		down(oneThird, hoursAgo(1));

		stats(twoThirds.getId(), "24h").andExpect(jsonPath("$.uptimePercent").value(66.67));
		stats(oneThird.getId(), "24h").andExpect(jsonPath("$.uptimePercent").value(33.33));
	}

	@Test
	@DisplayName("all checks failed: uptime is 0, not null; and there is no latency at all")
	void allFailed() throws Exception {
		Site site = site("always-down");
		down(site, hoursAgo(3));
		down(site, hoursAgo(2));

		stats(site.getId(), "24h")
				.andExpect(jsonPath("$.totalChecks").value(2))
				.andExpect(jsonPath("$.successfulChecks").value(0))
				.andExpect(jsonPath("$.uptimePercent").value(0.0))
				.andExpect(jsonPath("$.avgLatencyMs").value(nullValue()))
				.andExpect(jsonPath("$.p95LatencyMs").value(nullValue()));
	}

	@Test
	@DisplayName("every check up: exactly 100")
	void allUp() throws Exception {
		Site site = site("always-up");
		up(site, hoursAgo(3), 100);
		up(site, hoursAgo(2), 100);

		stats(site.getId(), "24h").andExpect(jsonPath("$.uptimePercent").value(100.0));
	}

	@Test
	@DisplayName("no checks in the period: uptime and latencies are null (never an invented 100 %), counts are zero")
	void noChecks() throws Exception {
		Site site = site("quiet");
		up(site, NOW.minus(Duration.ofDays(2)), 100); // exists, but outside the 24h period

		String json = stats(site.getId(), "24h").andExpect(status().isOk()).andExpect(jsonPath("$.totalChecks").value(0))
				.andExpect(jsonPath("$.successfulChecks").value(0))
				.andExpect(jsonPath("$.uptimePercent").value(nullValue()))
				.andExpect(jsonPath("$.avgLatencyMs").value(nullValue()))
				.andExpect(jsonPath("$.p95LatencyMs").value(nullValue()))
				.andExpect(jsonPath("$.firstCheckAt").value(nullValue()))
				.andExpect(jsonPath("$.lastCheckAt").value(nullValue())).andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsKey("uptimePercent");
		stats(site("never-checked").getId(), "30d").andExpect(jsonPath("$.uptimePercent").value(nullValue()));
	}

	// ---- latency ----

	@Test
	@DisplayName("p95 and mean of known values: latencies 1..100 give mean 50.50 and p95 95.05")
	void knownPercentile() throws Exception {
		Site site = site("percentiles");
		for (int latency = 1; latency <= 100; latency++) {
			up(site, NOW.minusSeconds(60L * latency), latency);
		}

		stats(site.getId(), "24h")
				.andExpect(jsonPath("$.totalChecks").value(100))
				.andExpect(jsonPath("$.avgLatencyMs").value(50.5))
				.andExpect(jsonPath("$.p95LatencyMs").value(95.05));
	}

	@Test
	@DisplayName("percentile_cont interpolates: 100, 200, 300, 400 give p95 385 and mean 250")
	void interpolatedPercentile() throws Exception {
		Site site = site("interpolated");
		int minutes = 1;
		for (int latency : new int[] { 400, 100, 300, 200 }) { // inserted unsorted on purpose
			up(site, NOW.minusSeconds(60L * minutes++), latency);
		}

		stats(site.getId(), "24h").andExpect(jsonPath("$.avgLatencyMs").value(250.0)).andExpect(jsonPath("$.p95LatencyMs").value(385.0));
	}

	@Test
	@DisplayName("a single value is its own mean and p95; averages are rounded to two decimals")
	void singleValueAndRounding() throws Exception {
		Site single = site("single");
		up(single, hoursAgo(1), 123);
		Site rounding = site("rounding");
		up(rounding, hoursAgo(3), 100);
		up(rounding, hoursAgo(2), 100);
		up(rounding, hoursAgo(1), 101);

		stats(single.getId(), "24h").andExpect(jsonPath("$.avgLatencyMs").value(123.0)).andExpect(jsonPath("$.p95LatencyMs").value(123.0));
		stats(rounding.getId(), "24h").andExpect(jsonPath("$.avgLatencyMs").value(100.33));
	}

	@Test
	@DisplayName("latency counts only SUCCESSFUL checks that have a latency: failed checks (even slow ones) and nulls are ignored")
	void latencyIgnoresFailedAndMissing() throws Exception {
		Site site = site("selective");
		up(site, hoursAgo(5), 100);
		up(site, hoursAgo(4), 300);
		down(site, hoursAgo(3)); // latency 5000 ms, but it failed
		upWithoutLatency(site, hoursAgo(2)); // up, no latency recorded

		stats(site.getId(), "24h")
				.andExpect(jsonPath("$.totalChecks").value(4))
				.andExpect(jsonPath("$.successfulChecks").value(3))
				.andExpect(jsonPath("$.uptimePercent").value(75.0))
				.andExpect(jsonPath("$.avgLatencyMs").value(200.0))
				.andExpect(jsonPath("$.p95LatencyMs").value(290.0));
	}

	// ---- period boundaries ----

	@Test
	@DisplayName("24h: a check exactly 24 h old is inside, one second older is outside; 'now' itself is inside, the future is not")
	void boundaryOf24Hours() throws Exception {
		Site site = site("boundary-24h");
		up(site, NOW.minus(Duration.ofHours(24)).minusSeconds(1), 111); // outside
		up(site, NOW.minus(Duration.ofHours(24)), 222); // exactly on the limit: inside
		up(site, NOW.minus(Duration.ofHours(24)).plusSeconds(1), 333); // inside
		up(site, NOW, 444); // now: inside
		down(site, NOW.plusSeconds(1)); // the future: never counted

		stats(site.getId(), "24h")
				.andExpect(jsonPath("$.period").value("24h"))
				.andExpect(jsonPath("$.windowStart").value(NOW.minus(Duration.ofHours(24)).toString()))
				.andExpect(jsonPath("$.windowEnd").value(NOW.toString()))
				.andExpect(jsonPath("$.totalChecks").value(3))
				.andExpect(jsonPath("$.uptimePercent").value(100.0))
				.andExpect(jsonPath("$.avgLatencyMs").value(333.0))
				.andExpect(jsonPath("$.firstCheckAt").value(NOW.minus(Duration.ofHours(24)).toString()))
				.andExpect(jsonPath("$.lastCheckAt").value(NOW.toString()));
	}

	@Test
	@DisplayName("7d and 30d have their own exact boundaries")
	void boundariesOf7And30Days() throws Exception {
		Site site = site("boundaries");
		Instant sevenDays = NOW.minus(Duration.ofDays(7));
		Instant thirtyDays = NOW.minus(Duration.ofDays(30));
		up(site, thirtyDays.minusSeconds(1), 1); // outside everything
		up(site, thirtyDays, 2); // only 30d
		up(site, sevenDays.minusSeconds(1), 3); // 30d only
		up(site, sevenDays, 4); // 7d and 30d
		up(site, hoursAgo(25), 5); // 7d and 30d, not 24h
		up(site, hoursAgo(1), 6); // all of them

		stats(site.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(1));
		stats(site.getId(), "7d").andExpect(jsonPath("$.totalChecks").value(3)).andExpect(jsonPath("$.windowStart").value(sevenDays.toString()));
		stats(site.getId(), "30d").andExpect(jsonPath("$.totalChecks").value(5)).andExpect(jsonPath("$.windowStart").value(thirtyDays.toString()))
				.andExpect(jsonPath("$.firstCheckAt").value(thirtyDays.toString()));
	}

	@Test
	@DisplayName("the window follows the clock: the same data counts differently when 'now' moves")
	void windowFollowsTheClock() throws Exception {
		Site site = site("moving");
		up(site, NOW.minus(Duration.ofHours(2)), 100);

		stats(site.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(1));
		clock.fix(NOW.plus(Duration.ofHours(22))); // the check is exactly 24 h old: still inside
		stats(site.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(1));
		clock.fix(NOW.plus(Duration.ofHours(22)).plusSeconds(1)); // one second older: outside
		stats(site.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(0)).andExpect(jsonPath("$.uptimePercent").value(nullValue()));
	}

	@Test
	@DisplayName("period is optional (24h) and case-insensitive; the response names the period used")
	void defaultAndCaseInsensitivePeriod() throws Exception {
		Site site = site("periods");
		up(site, hoursAgo(30), 100);
		up(site, hoursAgo(1), 100);

		stats(site.getId()).andExpect(jsonPath("$.period").value("24h")).andExpect(jsonPath("$.totalChecks").value(1));
		stats(site.getId(), "").andExpect(jsonPath("$.period").value("24h"));
		stats(site.getId(), "7D").andExpect(jsonPath("$.period").value("7d")).andExpect(jsonPath("$.totalChecks").value(2));
	}

	@ParameterizedTest(name = "period \"{0}\" is a 400")
	@ValueSource(strings = { "1h", "24", "90d", "7days", "abc", "-7d", "0d", "24h; drop table checks" })
	void invalidPeriod(String period) throws Exception {
		Site site = site("bad-period");

		stats(site.getId(), period)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value("period must be one of 24h, 7d, 30d"));
	}

	// ---- isolation, content, errors ----

	@Test
	@DisplayName("only the site's own checks are counted")
	void siteIsolation() throws Exception {
		Site mine = site("mine");
		Site other = site("other");
		up(mine, hoursAgo(1), 100);
		down(other, hoursAgo(1));
		down(other, hoursAgo(2));

		stats(mine.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(1)).andExpect(jsonPath("$.uptimePercent").value(100.0));
		stats(other.getId(), "24h").andExpect(jsonPath("$.totalChecks").value(2)).andExpect(jsonPath("$.uptimePercent").value(0.0));
	}

	@Test
	@DisplayName("the response contains exactly the documented fields, and the real range covered by the data")
	void contentShape() throws Exception {
		Site site = site("shape");
		up(site, hoursAgo(10), 100);
		down(site, hoursAgo(3));

		String json = stats(site.getId(), "24h").andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("siteId", "period", "windowStart", "windowEnd",
				"totalChecks", "successfulChecks", "uptimePercent", "avgLatencyMs", "p95LatencyMs", "firstCheckAt", "lastCheckAt");
		assertThat(JsonPath.<String>read(json, "$.firstCheckAt")).isEqualTo(hoursAgo(10).toString());
		assertThat(JsonPath.<String>read(json, "$.lastCheckAt")).isEqualTo(hoursAgo(3).toString());
	}

	@Test
	@DisplayName("an unknown site is a 404 ProblemDetail (a bad period is still a 400: parameters are validated first)")
	void unknownSite() throws Exception {
		stats(999_999, "24h").andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404));
		stats(999_999, "bogus").andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("it requires a token and only GET exists")
	void authentication() throws Exception {
		mvc.perform(get("/api/sites/1/stats")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/sites/1/stats").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
	}
}
