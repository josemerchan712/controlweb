package dev.josemariamerchan.controlweb.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.common.net.HostResolver;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * SSRF protection as seen from the HTTP API: the production setting
 * ({@code app.checker.allow-private-hosts=false}) with DNS simulated by a fake {@link HostResolver}.
 */
@SpringBootTest(properties = "app.checker.allow-private-hosts=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SiteApiSsrfIntegrationTest {

	private static final Map<String, String> FAKE_DNS = Map.of(
			"public.example.com", "93.184.216.34",
			"internal.example.com", "10.0.0.5",
			"metadata.example.com", "169.254.169.254",
			"loopback.example.com", "127.0.0.1");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SiteRepository sites;

	@MockitoBean
	private HostResolver resolver;

	@BeforeEach
	void fakeDns() throws UnknownHostException {
		when(resolver.resolve(anyString())).thenAnswer(invocation -> {
			String host = invocation.getArgument(0);
			// Literals (127.0.0.1, [::1], ...) resolve to themselves; names come from the fake DNS table.
			String ip = FAKE_DNS.getOrDefault(host, host.replace("[", "").replace("]", ""));
			if (!ip.matches("[0-9.]+|[0-9a-fA-F:]+")) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(ip) };
		});
	}

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	private static String body(String url) {
		return "{\"name\": \"Site\", \"url\": \"%s\", \"checkIntervalSeconds\": 60}".formatted(url);
	}

	@Test
	@DisplayName("a public host is accepted")
	void publicHostAccepted() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("https://public.example.com")))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a name that resolves to a private address is rejected with 400 and not stored")
	void privateResolutionRejected() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("https://internal.example.com")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("internal.example.com")));

		assertThat(sites.count()).isZero();
	}

	@Test
	@DisplayName("the cloud metadata address (169.254.169.254) is rejected, by name and as a literal")
	void metadataEndpointRejected() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("http://metadata.example.com/latest")))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("http://169.254.169.254/latest/meta-data")))
				.andExpect(status().isBadRequest());

		assertThat(sites.count()).isZero();
	}

	@Test
	@DisplayName("loopback is rejected as a literal IPv4, a literal IPv6 and via DNS")
	void loopbackRejected() throws Exception {
		for (String url : new String[] { "http://127.0.0.1:8080", "http://[::1]:8080", "http://loopback.example.com" }) {
			mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body(url)))
					.andExpect(status().isBadRequest());
		}

		assertThat(sites.count()).isZero();
	}

	@Test
	@DisplayName("editing a site to point to a private host is rejected and the stored URL is unchanged")
	void updateToPrivateHostRejected() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("https://public.example.com")))
				.andExpect(status().isCreated());
		long id = sites.findAll().get(0).getId();

		mvc.perform(put("/api/sites/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body("http://internal.example.com")))
				.andExpect(status().isBadRequest());

		assertThat(sites.findById(id).orElseThrow().getUrl()).isEqualTo("https://public.example.com");
	}

	@Test
	@DisplayName("a host that does not resolve yet is accepted (nothing to attack; the checker will report it as down)")
	void unresolvableHostAccepted() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(body("https://nope.invalid")))
				.andExpect(status().isCreated());
	}
}
