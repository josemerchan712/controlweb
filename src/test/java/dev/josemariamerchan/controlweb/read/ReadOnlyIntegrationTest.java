package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import dev.josemariamerchan.controlweb.SqlStatementCounter;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.site.domain.Site;

/** The read API has no side effects: it neither writes to the database nor changes what it reads. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReadOnlyIntegrationTest extends ReadApiTestSupport {

	/** One hash of every row of every table, including timestamps such as updated_at / last_checked_at. */
	private String databaseFingerprint() {
		return jdbc.sql("""
				select md5(
				  coalesce((select string_agg(t::text, '|' order by id) from sites t), '') ||
				  coalesce((select string_agg(t::text, '|' order by id) from checks t), '') ||
				  coalesce((select string_agg(t::text, '|' order by id) from incidents t), '') ||
				  coalesce((select string_agg(t::text, '|' order by id) from alert_rules t), '') ||
				  coalesce((select string_agg(t::text, '|' order by id) from users t), ''))
				""").query(String.class).single();
	}

	@Test
	@DisplayName("calling every read endpoint, repeatedly, issues only SELECTs and leaves every row untouched")
	void readEndpointsDoNotModifyAnything() throws Exception {
		clock.fix(NOW);
		Instant t = NOW.minus(Duration.ofHours(2));
		Site healthy = site("healthy");
		up(healthy, t, 120);
		checks.saveAndFlush(Check.down(healthy, t.plusSeconds(60), 503, null, "HTTP 503"));
		Site slow = site("slow");
		up(slow, t, 4000);
		var open = openIncident(slow, IncidentType.HIGH_LATENCY, t);
		var resolved = resolvedIncident(healthy, IncidentType.DOWN, t.minusSeconds(3600), 300);
		site("never-checked");
		site("paused", false);
		String before = databaseFingerprint();

		SqlStatementCounter.reset();
		for (int round = 0; round < 2; round++) {
			String[] urls = { "/api/status", "/api/sites/" + healthy.getId() + "/checks", "/api/sites/" + healthy.getId() + "/checks?size=1&page=1",
					"/api/sites/" + healthy.getId() + "/stats", "/api/sites/" + slow.getId() + "/stats?period=30d", "/api/incidents",
					"/api/incidents?status=open", "/api/incidents?siteId=" + healthy.getId(), "/api/incidents/" + open.getId(),
					"/api/incidents/" + resolved.getId() };
			for (String url : urls) {
				mvc.perform(get(url).with(TestTokens.bearer())).andExpect(status().isOk());
			}
		}
		List<String> statements = SqlStatementCounter.statements();

		assertThat(statements).isNotEmpty();
		assertThat(statements).as("every statement issued by the read API:%n%s", String.join("\n---\n", statements))
				.allSatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).startsWith("select"));
		assertThat(databaseFingerprint()).as("the database is exactly as it was").isEqualTo(before);
	}
}
