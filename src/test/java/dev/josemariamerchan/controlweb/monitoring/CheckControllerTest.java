package dev.josemariamerchan.controlweb.monitoring;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.monitoring.controller.CheckController;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.monitoring.service.CheckInProgressException;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

@WebMvcTest(CheckController.class)
@AutoConfigureMockMvc(addFilters = false) // authentication is covered by the security tests
class CheckControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private CheckRunner runner;

	@Test
	@DisplayName("POST /api/sites/{id}/check returns the stored result")
	void returnsTheResult() throws Exception {
		Instant checkedAt = Instant.parse("2026-03-01T12:00:00Z");
		when(runner.runManually(7L)).thenReturn(new CheckResponse(11L, 7L, checkedAt, false, 503, 90, "HTTP 503",
				Instant.parse("2026-05-01T00:00:00Z")));

		mvc.perform(post("/api/sites/7/check"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(11))
				.andExpect(jsonPath("$.siteId").value(7))
				.andExpect(jsonPath("$.up").value(false))
				.andExpect(jsonPath("$.statusCode").value(503))
				.andExpect(jsonPath("$.latencyMs").value(90))
				.andExpect(jsonPath("$.errorMessage").value("HTTP 503"))
				.andExpect(jsonPath("$.certExpiresAt").isNotEmpty());
	}

	@Test
	@DisplayName("an unknown site is a 404 problem")
	void unknownSite() throws Exception {
		when(runner.runManually(99L)).thenThrow(new SiteNotFoundException(99L));

		mvc.perform(post("/api/sites/99/check"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("a check already in progress is a 409 problem")
	void checkInProgress() throws Exception {
		when(runner.runManually(7L)).thenThrow(new CheckInProgressException(7L));

		mvc.perform(post("/api/sites/7/check"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("A check of site 7 is already in progress"));
	}

	@Test
	@DisplayName("a non-numeric id is a 400, and GET is not allowed")
	void badRequests() throws Exception {
		mvc.perform(post("/api/sites/abc/check")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/sites/7/check")).andExpect(status().isMethodNotAllowed());
	}
}
