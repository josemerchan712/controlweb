package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/** GET /api/sites/{id}/rules and PUT /api/sites/{id}/rules/{type}: validation, errors and authentication. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RuleApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private AlertRuleRepository rules;

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	private long createSite() throws Exception {
		String body = mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Rules\", \"url\": \"https://rules.example.com\", \"checkIntervalSeconds\": 60}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	private org.springframework.test.web.servlet.ResultActions putRule(long siteId, String type, String json) throws Exception {
		return mvc.perform(put("/api/sites/{id}/rules/{type}", siteId, type).with(TestTokens.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	@DisplayName("a new site has the three default rules, listed in a stable order with their unit")
	void listsDefaultRules() throws Exception {
		long siteId = createSite();

		mvc.perform(get("/api/sites/{id}/rules", siteId).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].type").value("DOWN"))
				.andExpect(jsonPath("$[0].threshold").value(2))
				.andExpect(jsonPath("$[0].enabled").value(true))
				.andExpect(jsonPath("$[1].type").value("HIGH_LATENCY"))
				.andExpect(jsonPath("$[1].threshold").value(2000))
				.andExpect(jsonPath("$[1].unit").value("ms"))
				.andExpect(jsonPath("$[2].type").value("SSL_EXPIRING"))
				.andExpect(jsonPath("$[2].threshold").value(14))
				.andExpect(jsonPath("$[2].unit").value("days"));
	}

	@Test
	@DisplayName("a rule can be tuned and persisted; omitting 'enabled' keeps its state")
	void updatesRule() throws Exception {
		long siteId = createSite();

		putRule(siteId, "DOWN", "{\"threshold\": 5}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("DOWN"))
				.andExpect(jsonPath("$.threshold").value(5))
				.andExpect(jsonPath("$.enabled").value(true));
		putRule(siteId, "DOWN", "{\"threshold\": 5, \"enabled\": false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));
		putRule(siteId, "DOWN", "{\"threshold\": 3}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.threshold").value(3))
				.andExpect(jsonPath("$.enabled").value(false));

		assertThat(rules.findBySiteIdAndType(siteId, IncidentType.DOWN)).hasValueSatisfying(rule -> {
			assertThat(rule.getThreshold()).isEqualTo(3);
			assertThat(rule.isEnabled()).isFalse();
		});
		assertThat(rules.findBySiteIdAndType(siteId, IncidentType.HIGH_LATENCY)).hasValueSatisfying(rule -> {
			assertThat(rule.getThreshold()).as("other rules are untouched").isEqualTo(2000);
		});
	}

	@ParameterizedTest(name = "{0} threshold {1} is accepted")
	@CsvSource({ "DOWN,1", "DOWN,100", "HIGH_LATENCY,1", "HIGH_LATENCY,600000", "SSL_EXPIRING,1", "SSL_EXPIRING,365" })
	void acceptsBoundaryThresholds(String type, int threshold) throws Exception {
		long siteId = createSite();

		putRule(siteId, type, "{\"threshold\": %d}".formatted(threshold))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.threshold").value(threshold));
	}

	@ParameterizedTest(name = "{0} threshold {1} is rejected")
	@CsvSource({ "DOWN,0", "DOWN,-3", "DOWN,101", "HIGH_LATENCY,0", "HIGH_LATENCY,600001", "SSL_EXPIRING,0", "SSL_EXPIRING,366" })
	void rejectsThresholdsOutOfRange(String type, int threshold) throws Exception {
		long siteId = createSite();

		putRule(siteId, type, "{\"threshold\": %d}".formatted(threshold))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(rules.findBySiteIdAndType(siteId, IncidentType.valueOf(type)).orElseThrow().getThreshold())
				.as("a rejected change leaves the rule as it was").isNotEqualTo(threshold);
	}

	@Test
	@DisplayName("the error explains the allowed range of the rule type")
	void rangeIsExplained() throws Exception {
		long siteId = createSite();

		putRule(siteId, "SSL_EXPIRING", "{\"threshold\": 1000}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("SSL_EXPIRING")))
				.andExpect(jsonPath("$.detail").value(containsString("between 1 and 365")));
	}

	@Test
	@DisplayName("a missing or malformed threshold is a 400 with the offending field")
	void missingThreshold() throws Exception {
		long siteId = createSite();

		putRule(siteId, "DOWN", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.threshold").exists());
		putRule(siteId, "DOWN", "{\"threshold\": null}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.threshold").exists());
		putRule(siteId, "DOWN", "{\"threshold\": \"many\"}").andExpect(status().isBadRequest());
		putRule(siteId, "DOWN", "{broken").andExpect(status().isBadRequest());
	}

	@ParameterizedTest(name = "type \"{0}\" is rejected")
	@CsvSource({ "FOO", "down", "SSL", "1" })
	void rejectsUnknownTypes(String type) throws Exception {
		long siteId = createSite();

		putRule(siteId, type, "{\"threshold\": 5}").andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("an unknown site is a 404 for both endpoints")
	void unknownSite() throws Exception {
		mvc.perform(get("/api/sites/{id}/rules", 999_999).with(TestTokens.bearer())).andExpect(status().isNotFound());
		putRule(999_999, "DOWN", "{\"threshold\": 5}").andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("both endpoints require a token")
	void requireAuthentication() throws Exception {
		long siteId = createSite();

		mvc.perform(get("/api/sites/{id}/rules", siteId)).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/sites/{id}/rules/DOWN", siteId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"threshold\": 5}")).andExpect(status().isUnauthorized());
		assertThat(rules.findBySiteIdAndType(siteId, IncidentType.DOWN).orElseThrow().getThreshold()).isEqualTo(2);
	}
}
