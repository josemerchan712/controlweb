package dev.josemariamerchan.controlweb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Logger;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.RestoreLoggingLevels;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * The whole application started with the production profile (real application.yml + application-prod.yml, real PostgreSQL
 * from Testcontainers, a real HTTP port). Only what a deployment must set from outside is provided here, with
 * non-placeholder values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.proxy.trusted-addresses=10.77.0.10",
		"app.public.cors-allowed-origins=https://josemariamerchan.dev",
		// the real connection comes from Testcontainers; this value only has to look like a real password to the check
		"spring.datasource.password=Zk3-not-a-placeholder-db-password" })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(RestoreLoggingLevels.class)
class ProdProfileIntegrationTest {

	private static final String QUERY_SECRET = "QSECRET-9f3a1c";
	private static final String BAD_BEARER_SECRET = "BEARER-SECRET-7c1d";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private Environment environment;

	@Autowired
	private SiteRepository sites;

	@LocalServerPort
	private int port;

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	// ---- the profile ----

	@Test
	@DisplayName("the application starts with exactly the prod profile (the 'test' profile of the suite did not leak in)")
	void onlyProdIsActive() {
		assertThat(environment.getActiveProfiles()).containsExactly("prod");
		assertThat(environment.getProperty("app.checker.allow-private-hosts", Boolean.class)).isFalse();
		assertThat(environment.getProperty("app.scheduler.enabled", Boolean.class)).isTrue();
		assertThat(environment.getProperty("app.retention.enabled", Boolean.class)).isTrue();
	}

	// ---- Actuator ----

	@Test
	@DisplayName("GET /actuator/health is public and says UP and nothing else: no components, no details")
	void healthIsStatusOnly() throws Exception {
		assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
		assertThat(environment.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(content().json("{\"status\":\"UP\"}", true));
		mvc.perform(get("/actuator/health").with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", true));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator", "/actuator/env", "/actuator/env/app.jwt.secret", "/actuator/beans", "/actuator/metrics",
			"/actuator/loggers", "/actuator/mappings", "/actuator/configprops", "/actuator/info", "/actuator/heapdump",
			"/actuator/threaddump", "/actuator/conditions", "/actuator/scheduledtasks", "/actuator/flyway",
			"/actuator/caches", "/actuator/httpexchanges", "/actuator/shutdown", "/actuator/health/db",
			"/actuator/health/diskSpace" })
	@DisplayName("every other actuator endpoint does not exist: 404 even for the administrator")
	void otherActuatorEndpointsDoNotExist(String path) throws Exception {
		mvc.perform(get(path).with(TestTokens.bearer())).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("without a token those paths are indistinguishable from any unknown path (401), so nothing reveals what exists")
	void actuatorPathsAreIndistinguishableWithoutToken() throws Exception {
		int unknown = mvc.perform(get("/actuator/definitely-not-there")).andReturn().getResponse().getStatus();

		for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/loggers")) {
			assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(unknown).isEqualTo(401);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html" })
	@DisplayName("Swagger and the OpenAPI document do not exist in production: 404 for the administrator, 401 for everybody else")
	void swaggerDoesNotExist(String path) throws Exception {
		mvc.perform(get(path).with(TestTokens.bearer())).andExpect(status().isNotFound());
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
	}

	// ---- logging configuration ----

	@ParameterizedTest
	@ValueSource(strings = { Logger.ROOT_LOGGER_NAME, "org.hibernate.SQL", "org.hibernate.orm.jdbc.bind", "org.hibernate.orm.jdbc.extract",
			"org.springframework.security", "org.springframework.security.web.FilterChainProxy",
			"org.springframework.security.web.access.ExceptionTranslationFilter", "org.springframework.web",
			"org.springframework.web.servlet.DispatcherServlet", "org.springframework.web.filter.CommonsRequestLoggingFilter",
			"org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.StatementCreatorUtils",
			"org.apache.coyote.http11.Http11Processor", "org.apache.tomcat.util.net.NioEndpoint",
			"org.apache.catalina.core.ContainerBase", "dev.josemariamerchan.controlweb" })
	@DisplayName("production logging: no DEBUG or TRACE for SQL, bound parameters, Spring Security, Spring Web or Tomcat")
	void quietLoggers(String name) {
		Logger logger = (Logger) LoggerFactory.getLogger(name);

		assertThat(logger.isDebugEnabled()).as("DEBUG enabled for %s", name).isFalse();
		assertThat(logger.isTraceEnabled()).as("TRACE enabled for %s", name).isFalse();
	}

	@Test
	@DisplayName("show-sql is off")
	void showSqlIsOff() {
		assertThat(environment.getProperty("spring.jpa.show-sql", Boolean.class, false)).isFalse();
	}

	@Test
	@DisplayName("nothing sensitive is logged: no query string, no Authorization header or token, in any path a request can take")
	void noUrlQueryAndNoAuthorizationInLogs() throws Exception {
		String validBearer = TestTokens.adminToken();
		HttpClient client = HttpClient.newHttpClient();
		String base = "http://localhost:" + port;
		String json = "application/json";

		try (LogCapture logs = LogCapture.atConfiguredLevels()) {
			// authenticated, with a query string full of secrets
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites?token=" + QUERY_SECRET + "&api_key=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).GET().build(), 200);
			// 400: wrong parameter type
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites/abc?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).GET().build(), 400);
			// 404: unknown path, with and without credentials
			send(client, HttpRequest.newBuilder(URI.create(base + "/nope?password=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).GET().build(), 404);
			send(client, HttpRequest.newBuilder(URI.create(base + "/nope?password=" + QUERY_SECRET)).GET().build(), 401);
			// 401: garbage and expired-looking tokens
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + BAD_BEARER_SECRET).GET().build(), 401);
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites?token=" + QUERY_SECRET))
					.header("Authorization", "Basic " + BAD_BEARER_SECRET).GET().build(), 401);
			// login: broken JSON, wrong credentials
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/auth/login?token=" + QUERY_SECRET))
					.header("Content-Type", json).POST(HttpRequest.BodyPublishers.ofString("{bad " + QUERY_SECRET)).build(), 400);
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/auth/login?token=" + QUERY_SECRET))
					.header("Content-Type", json)
					.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + TestCredentials.ADMIN_EMAIL
							+ "\",\"password\":\"wrong-" + QUERY_SECRET + "\"}"))
					.build(), 401);
			// 405 and an invalid body
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/public/status?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + BAD_BEARER_SECRET).DELETE().build(), 405);
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).header("Content-Type", json)
					.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\",\"url\":\"nope\"}")).build(), 400);
			// an address the SSRF guard refuses, carrying a secret in its query string
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites"))
					.header("Authorization", "Bearer " + validBearer).header("Content-Type", json)
					.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"internal\",\"url\":\"http://10.0.0.5/admin?token="
							+ QUERY_SECRET + "\",\"checkIntervalSeconds\":60}"))
					.build(), 400);
			// URLs the servlet container or the firewall rejects
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites;jsessionid=" + QUERY_SECRET + "?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).GET().build(), -1);
			sendRaw("GET /api/sites?x=%zz&token=" + QUERY_SECRET + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
					+ BAD_BEARER_SECRET + "\r\nConnection: close\r\n\r\n");
			sendRaw("GET /api/sites?token=" + QUERY_SECRET + " HTTP/1.1\r\nHost: localhost\r\nBad Header Line "
					+ BAD_BEARER_SECRET + "\r\nConnection: close\r\n\r\n");
			// a site whose URL carries a secret in its query string, checked by hand until an incident opens
			String created = send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites"))
					.header("Authorization", "Bearer " + validBearer).header("Content-Type", json)
					.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"secretive\",\"url\":\"https://no-such-host.invalid/health?token="
							+ QUERY_SECRET + "\",\"checkIntervalSeconds\":3600}"))
					.build(), 201);
			String id = created.replaceAll(".*\"id\":(\\d+).*", "$1");
			for (int i = 0; i < 3; i++) {
				send(client, HttpRequest.newBuilder(URI.create(base + "/api/sites/" + id + "/check"))
						.header("Authorization", "Bearer " + validBearer).POST(HttpRequest.BodyPublishers.noBody()).build(), 200);
			}
			send(client, HttpRequest.newBuilder(URI.create(base + "/api/incidents?token=" + QUERY_SECRET))
					.header("Authorization", "Bearer " + validBearer).GET().build(), 200);

			List<String> everything = logs.fullText();
			assertThat(everything).as("the capture must work, or this test proves nothing: the incident log line must be there")
					.anyMatch(line -> line.contains("Incident") && line.contains("opened"));
			assertThat(everything).noneMatch(line -> line.contains(QUERY_SECRET));
			assertThat(everything).noneMatch(line -> line.contains(BAD_BEARER_SECRET));
			assertThat(everything).noneMatch(line -> line.contains(validBearer));
			assertThat(everything).noneMatch(line -> line.toLowerCase().contains("authorization"));
			assertThat(everything).noneMatch(line -> line.contains("?token=") || line.contains("&token=")
					|| line.contains("?password=") || line.contains("api_key="));
			assertThat(everything).noneMatch(line -> line.toLowerCase().matches("(?s).*\\b(select|insert|update|delete)\\b.* (from|into|set) .*"));
		}
	}

	/** Sends bytes the HTTP client would refuse to build (a malformed escape, a broken header) and reads the status line. */
	private void sendRaw(String request) throws Exception {
		try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			socket.getInputStream().readAllBytes();
		}
	}

	/** Sends the request; {@code expected} -1 means "any status, I only care about the logs". Returns the body. */
	private static String send(HttpClient client, HttpRequest request, int expected) throws Exception {
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (expected != -1) {
			assertThat(response.statusCode()).as("%s %s", request.method(), request.uri().getPath()).isEqualTo(expected);
		}
		return response.body();
	}
}
