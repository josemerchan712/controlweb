package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;

/**
 * Guard: walks every {@code /api/**} endpoint registered in the application and checks that, without
 * a token, it answers 401 -- except the explicitly public ones. A new endpoint that is exposed by
 * mistake (e.g. through a too-wide permitAll rule) breaks the build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiSecurityGuardTest {

	/** The ONLY public API endpoints. Adding to this list is a conscious security decision. */
	private static boolean isPublic(HttpMethod method, String pattern) {
		return (method == HttpMethod.POST && pattern.equals("/api/auth/login"))
				|| (method == HttpMethod.GET && pattern.equals("/api/public/status"));
	}

	private record Endpoint(HttpMethod method, String pattern) {
		String concretePath() {
			return pattern.replaceAll("\\{[^}]+}", "1");
		}

		@Override
		public String toString() {
			return method + " " + pattern;
		}
	}

	@Autowired
	private MockMvc mvc;

	// Actuator registers its own handler mapping too; the application's controllers are in this one.
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private List<Endpoint> apiEndpoints() {
		List<Endpoint> endpoints = new ArrayList<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			if (info.getPathPatternsCondition() == null) {
				continue;
			}
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (!pattern.startsWith("/api/")) {
					continue;
				}
				if (methods.isEmpty()) {
					endpoints.add(new Endpoint(HttpMethod.GET, pattern));
				}
				for (RequestMethod method : methods) {
					endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()), pattern));
				}
			}
		}
		return endpoints;
	}

	@Test
	@DisplayName("sanity: the guard discovers the API (so it cannot pass vacuously)")
	void guardDiscoversTheApi() {
		Set<String> found = apiEndpoints().stream().map(Endpoint::toString).collect(Collectors.toCollection(TreeSet::new));

		assertThat(found).contains("POST /api/auth/login", "GET /api/auth/me", "GET /api/sites", "POST /api/sites",
				"GET /api/sites/{id}", "PUT /api/sites/{id}", "DELETE /api/sites/{id}", "POST /api/sites/{id}/check", "GET /api/sites/{siteId}/rules",
				"PUT /api/sites/{siteId}/rules/{type}", "POST /api/alerts/test", "GET /api/status",
				"GET /api/sites/{siteId}/checks", "GET /api/sites/{siteId}/stats", "GET /api/incidents", "GET /api/incidents/{id}", "GET /api/public/status");
	}

	@Test
	@DisplayName("every /api endpoint answers 401 without a token, except the public ones")
	void everyNonPublicEndpointRequiresAuthentication() throws Exception {
		List<String> unprotected = new ArrayList<>();
		for (Endpoint endpoint : apiEndpoints()) {
			if (isPublic(endpoint.method(), endpoint.pattern())) {
				continue;
			}
			int status = mvc.perform(request(endpoint.method(), endpoint.concretePath())).andReturn().getResponse().getStatus();
			if (status != 401) {
				unprotected.add(endpoint + " answered " + status + " without a token");
			}
		}

		assertThat(unprotected).as("endpoints reachable without authentication").isEmpty();
	}

	@Test
	@DisplayName("the public endpoints really are public (not 401/403 without a token)")
	void publicEndpointsAreReachable() throws Exception {
		List<String> blocked = new ArrayList<>();
		for (Endpoint endpoint : apiEndpoints()) {
			if (!isPublic(endpoint.method(), endpoint.pattern())) {
				continue;
			}
			int status = mvc.perform(request(endpoint.method(), endpoint.concretePath())).andReturn().getResponse().getStatus();
			if (status == 401 || status == 403) {
				blocked.add(endpoint + " answered " + status);
			}
		}

		assertThat(blocked).isEmpty();
	}

	@Test
	@DisplayName("with a valid token no /api endpoint answers 401/403 (authorization is not over-restrictive)")
	void validTokenOpensEveryEndpoint() throws Exception {
		List<String> denied = new ArrayList<>();
		for (Endpoint endpoint : apiEndpoints()) {
			int status = mvc.perform(request(endpoint.method(), endpoint.concretePath()).with(TestTokens.bearer()))
					.andReturn().getResponse().getStatus();
			if (status == 401 || status == 403) {
				denied.add(endpoint + " answered " + status + " with a valid token");
			}
		}

		assertThat(denied).isEmpty();
	}

	@Test
	@DisplayName("unknown /api paths and the root are also 401 without a token (default deny)")
	void unknownPathsAreDeniedByDefault() throws Exception {
		for (String path : List.of("/api/does-not-exist", "/api/sites/1/anything", "/", "/anything", "/error", "/actuator/env",
				"/api/public", "/api/public/", "/api/public/other", "/api/public/status/extra")) {
			assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
		}
	}
}
