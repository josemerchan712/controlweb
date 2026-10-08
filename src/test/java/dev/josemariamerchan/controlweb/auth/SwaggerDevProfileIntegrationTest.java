package dev.josemariamerchan.controlweb.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestcontainersConfiguration;

/** With the {@code dev} profile the API documentation is public; the API itself stays protected. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "dev" })
@Import(TestcontainersConfiguration.class)
class SwaggerDevProfileIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Test
	@DisplayName("the OpenAPI document is public in dev and describes the API")
	void apiDocsArePublicInDev() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/api/sites")));
	}

	@Test
	@DisplayName("the Swagger UI is public in dev")
	void swaggerUiIsPublicInDev() throws Exception {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("even in dev the API remains protected")
	void apiStillProtectedInDev() throws Exception {
		mvc.perform(get("/api/sites")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}
}
