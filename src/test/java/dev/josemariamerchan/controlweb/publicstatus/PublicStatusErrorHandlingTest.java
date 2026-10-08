package dev.josemariamerchan.controlweb.publicstatus;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.config.PublicProperties;

/** Failures of the public endpoint are fixed, generic ProblemDetails: nothing internal can reach an anonymous caller. */
@WebMvcTest(PublicStatusController.class)
@AutoConfigureMockMvc(addFilters = false) // authentication is covered by the security tests
@Import(PublicStatusErrorHandlingTest.Properties.class)
class PublicStatusErrorHandlingTest {

	/** Web slices do not register @ConfigurationProperties classes by themselves. */
	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PublicProperties.class)
	static class Properties {
	}

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PublicStatusCache cache;

	@Test
	@DisplayName("an unexpected failure is a 500 problem+json with a fixed message: no class, SQL, host or password")
	void unexpectedFailureIsGeneric() throws Exception {
		when(cache.get()).thenThrow(new IllegalStateException(
				"could not execute query: select * from sites; jdbc:postgresql://db.internal:5432/controlweb password=hunter2 at C:\\secret\\Path.java"));

		mvc.perform(get("/api/public/status"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(content().string(not(containsString("hunter2"))))
				.andExpect(content().string(not(containsString("jdbc"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))))
				.andExpect(content().string(not(containsString("select"))))
				.andExpect(content().string(not(containsString("db.internal"))))
				.andExpect(content().string(not(containsString("Path.java"))));
	}

	@Test
	@DisplayName("when nothing can be served the answer is a plain 503 with Retry-After")
	void unavailableIsA503() throws Exception {
		when(cache.get()).thenThrow(new PublicStatusUnavailableException());

		mvc.perform(get("/api/public/status"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(header().string("Retry-After", "15"))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.detail").value("The status is temporarily unavailable"));
	}
}
