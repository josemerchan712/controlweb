package dev.josemariamerchan.controlweb.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * End to end through the HTTP layer, the service and a real PostgreSQL. Runs with the "test"
 * profile, where private hosts are allowed (see application-test.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SiteApiIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SiteRepository sites;

	@Autowired
	private CheckRepository checks;

	@Autowired
	private AlertRuleRepository rules;

	@Autowired
	private IncidentRepository incidents;

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	private long createSite(String name, String url) throws Exception {
		String body = mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"url\": \"%s\", \"checkIntervalSeconds\": 60}".formatted(name, url)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	@Test
	@DisplayName("full lifecycle: create, read, list, update and delete a site")
	void fullLifecycle() throws Exception {
		long id = createSite("Blog", "HTTPS://Blog.Example.com:443/#home");

		mvc.perform(get("/api/sites/{id}", id).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Blog"))
				.andExpect(jsonPath("$.url").value("https://blog.example.com"))
				.andExpect(jsonPath("$.checkIntervalSeconds").value(60))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.publicVisible").value(false));

		mvc.perform(get("/api/sites").with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mvc.perform(put("/api/sites/{id}", id).with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Blog v2", "url": "https://blog.example.com/health", "checkIntervalSeconds": 120,
				 "active": false, "publicVisible": true}
				"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Blog v2"))
				.andExpect(jsonPath("$.url").value("https://blog.example.com/health"))
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.publicVisible").value(true));

		mvc.perform(delete("/api/sites/{id}", id).with(TestTokens.bearer())).andExpect(status().isNoContent());
		mvc.perform(get("/api/sites/{id}", id).with(TestTokens.bearer())).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("creating a site also creates its three default alert rules")
	void createsDefaultRules() throws Exception {
		long id = createSite("Blog", "https://blog.example.com");

		assertThat(rules.findAll())
				.filteredOn(rule -> rule.getSite().getId() == id)
				.extracting(rule -> rule.getType(), rule -> rule.getThreshold())
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple(IncidentType.DOWN, 2),
						org.assertj.core.groups.Tuple.tuple(IncidentType.HIGH_LATENCY, 2000),
						org.assertj.core.groups.Tuple.tuple(IncidentType.SSL_EXPIRING, 14));
	}

	@Test
	@DisplayName("URL normalization makes the UNIQUE constraint effective: another spelling of the same URL is a 409")
	void differentSpellingOfSameUrlIsDuplicate() throws Exception {
		createSite("Blog", "https://blog.example.com");

		mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Blog again", "url": "HTTPS://BLOG.EXAMPLE.COM:443/#x", "checkIntervalSeconds": 60}
				"""))
				.andExpect(status().isConflict());

		assertThat(sites.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("updating a site to the URL of another site is a 409 and changes nothing")
	void updateToAnotherSitesUrlIsConflict() throws Exception {
		createSite("Blog", "https://blog.example.com");
		long shop = createSite("Shop", "https://shop.example.com");

		mvc.perform(put("/api/sites/{id}", shop).with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Shop", "url": "https://Blog.example.com/", "checkIntervalSeconds": 60}
				"""))
				.andExpect(status().isConflict());

		assertThat(sites.findById(shop).orElseThrow().getUrl()).isEqualTo("https://shop.example.com");
	}

	@Test
	@DisplayName("updating a site keeping its own URL is allowed")
	void updateKeepingOwnUrl() throws Exception {
		long id = createSite("Blog", "https://blog.example.com");

		mvc.perform(put("/api/sites/{id}", id).with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Renamed", "url": "https://BLOG.example.com/", "checkIntervalSeconds": 90}
				"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Renamed"));
	}

	@Test
	@DisplayName("embedded credentials are rejected with 400 and nothing is stored")
	void rejectsCredentials() throws Exception {
		mvc.perform(post("/api/sites").with(TestTokens.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Blog", "url": "http://admin:secret@blog.example.com", "checkIntervalSeconds": 60}
				"""))
				.andExpect(status().isBadRequest());

		assertThat(sites.count()).isZero();
	}

	@Test
	@DisplayName("with the test profile (private hosts allowed) a localhost site can be registered")
	void localhostAllowedInTestProfile() throws Exception {
		createSite("Local app", "http://localhost:8081");
	}

	@Test
	@DisplayName("DELETE removes the site's checks, rules and incidents (single deleteById + ON DELETE CASCADE)")
	void deleteRemovesChecksRulesAndIncidents() throws Exception {
		long id = createSite("Blog", "https://blog.example.com");
		long other = createSite("Shop", "https://shop.example.com");
		Site blog = sites.findById(id).orElseThrow();
		Site shop = sites.findById(other).orElseThrow();
		checks.save(Check.up(blog, T0, 200, 120, null));
		checks.save(Check.down(blog, T0.plusSeconds(60), null, null, "Connection refused"));
		incidents.save(Incident.open(blog, IncidentType.DOWN, T0, "Down"));
		checks.save(Check.up(shop, T0, 200, 90, null));
		incidents.save(Incident.open(shop, IncidentType.HIGH_LATENCY, T0, "Slow"));

		assertThat(rules.count()).isEqualTo(6);

		mvc.perform(delete("/api/sites/{id}", id).with(TestTokens.bearer())).andExpect(status().isNoContent());

		assertThat(sites.findById(id)).isEmpty();
		assertThat(checks.findAll()).extracting(check -> check.getSite().getId()).containsOnly(other);
		assertThat(incidents.findAll()).extracting(incident -> incident.getSite().getId()).containsOnly(other);
		assertThat(rules.findAll()).hasSize(3).extracting(rule -> rule.getSite().getId()).containsOnly(other);
	}

	@Test
	@DisplayName("DELETE of an unknown site is a 404")
	void deleteUnknown() throws Exception {
		mvc.perform(delete("/api/sites/{id}", 999_999).with(TestTokens.bearer())).andExpect(status().isNotFound());
	}
}
