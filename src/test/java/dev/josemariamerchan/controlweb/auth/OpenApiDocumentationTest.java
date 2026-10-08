package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.josemariamerchan.controlweb.TestcontainersConfiguration;

/** What the generated OpenAPI document promises (dev profile only: it is not served anywhere else). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "dev" })
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentationTest {

	private static final List<String> METHODS = List.of("get", "post", "put", "delete");

	@Autowired
	private MockMvc mvc;

	private Map<String, Object> document() throws Exception {
		String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return JsonPath.parse(json).read("$");
	}

	@SuppressWarnings("unchecked")
	private List<Map.Entry<String, Map<String, Object>>> operations(Map<String, Object> document) {
		Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
		return paths.entrySet().stream()
				.flatMap(path -> path.getValue().entrySet().stream().filter(e -> METHODS.contains(e.getKey()))
						.map(e -> Map.entry(e.getKey().toUpperCase() + " " + path.getKey(), (Map<String, Object>) e.getValue())))
				.toList();
	}

	@Test
	@DisplayName("declares the Bearer JWT scheme and requires it globally")
	void declaresBearerScheme() throws Exception {
		Map<String, Object> document = document();

		assertThat(JsonPath.<String>read(document, "$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
		assertThat(JsonPath.<String>read(document, "$.components.securitySchemes.bearerAuth.bearerFormat")).isEqualTo("JWT");
		assertThat(JsonPath.<List<Object>>read(document, "$.security[*].bearerAuth")).isNotEmpty();
	}

	@Test
	@DisplayName("every operation has a summary and a description")
	void everyOperationIsDescribed() throws Exception {
		List<Map.Entry<String, Map<String, Object>>> operations = operations(document());

		assertThat(operations).hasSizeGreaterThanOrEqualTo(15);
		for (Map.Entry<String, Map<String, Object>> operation : operations) {
			assertThat(operation.getValue().get("summary")).as(operation.getKey() + " summary").isInstanceOf(String.class);
			assertThat((String) operation.getValue().get("description")).as(operation.getKey() + " description")
					.isNotBlank();
		}
	}

	@Test
	@DisplayName("only login and the public status are documented as not needing a token")
	@SuppressWarnings("unchecked")
	void onlyTwoEndpointsAreOpen() throws Exception {
		List<String> open = operations(document()).stream()
				.filter(operation -> operation.getValue().get("security") instanceof List<?> security && security.isEmpty())
				.map(Map.Entry::getKey).toList();

		assertThat(open).containsExactlyInAnyOrder("POST /api/auth/login", "GET /api/public/status");
	}

	@Test
	@DisplayName("errors are documented as application/problem+json using the ProblemDetail schema")
	void errorsAreProblemDetails() throws Exception {
		Map<String, Object> document = document();

		assertThat(JsonPath.<Map<String, Object>>read(document, "$.components.schemas.ProblemDetail.properties"))
				.containsKeys("title", "status", "detail", "instance");
		assertThat(JsonPath.<String>read(document,
				"$.paths['/api/sites'].post.responses['400'].content['application/problem+json'].schema['$ref']"))
				.endsWith("/ProblemDetail");
		assertThat(JsonPath.<String>read(document,
				"$.paths['/api/sites/{id}'].get.responses['404'].content['application/problem+json'].schema['$ref']"))
				.endsWith("/ProblemDetail");
		assertThat(JsonPath.<String>read(document,
				"$.paths['/api/sites'].get.responses['401'].content['application/problem+json'].schema['$ref']"))
				.endsWith("/ProblemDetail");
		assertThat(JsonPath.<String>read(document,
				"$.paths['/api/alerts/test'].post.responses['429'].content['application/problem+json'].schema['$ref']"))
				.endsWith("/ProblemDetail");
	}

	@Test
	@DisplayName("login documents 401 for wrong credentials, and the public endpoint documents no 401")
	void publicEndpointsHaveNoAuthErrors() throws Exception {
		Map<String, Object> document = document();

		assertThat(JsonPath.<Map<String, Object>>read(document, "$.paths['/api/auth/login'].post.responses"))
				.containsKeys("401", "429");
		assertThat(JsonPath.<Map<String, Object>>read(document, "$.paths['/api/public/status'].get.responses"))
				.doesNotContainKey("401");
	}
}
