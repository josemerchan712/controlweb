package dev.josemariamerchan.controlweb.site;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.common.net.UnsafeHostException;
import dev.josemariamerchan.controlweb.site.controller.SiteController;
import dev.josemariamerchan.controlweb.site.dto.SiteRequest;
import dev.josemariamerchan.controlweb.site.dto.SiteResponse;
import dev.josemariamerchan.controlweb.site.service.DuplicateSiteException;
import dev.josemariamerchan.controlweb.site.service.InvalidSiteUrlException;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;
import dev.josemariamerchan.controlweb.site.service.SiteService;

@WebMvcTest(SiteController.class)
class SiteControllerTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final String VALID_BODY = """
			{"name": "Blog", "url": "https://blog.example.com", "checkIntervalSeconds": 60}
			""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private SiteService service;

	private static SiteResponse response(long id) {
		return new SiteResponse(id, "Blog", "https://blog.example.com", 60, true, false, null, T0, T0);
	}

	@Test
	@DisplayName("POST /api/sites creates a site: 201 with Location and the body")
	void createReturns201() throws Exception {
		when(service.create(any(SiteRequest.class))).thenReturn(response(5));

		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "http://localhost/api/sites/5"))
				.andExpect(jsonPath("$.id").value(5))
				.andExpect(jsonPath("$.url").value("https://blog.example.com"))
				.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	@DisplayName("POST passes the parsed request to the service")
	void createPassesRequestToService() throws Exception {
		when(service.create(any(SiteRequest.class))).thenReturn(response(5));

		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Blog", "url": "https://blog.example.com", "checkIntervalSeconds": 60,
				 "active": false, "publicVisible": true}
				""")).andExpect(status().isCreated());

		verify(service).create(new SiteRequest("Blog", "https://blog.example.com", 60, false, true));
	}

	@Test
	@DisplayName("POST with invalid fields: 400 problem+json listing each offending field")
	void createValidationErrors() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": " ", "checkIntervalSeconds": 10}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.url").exists())
				.andExpect(jsonPath("$.errors.checkIntervalSeconds").exists());
	}

	@Test
	@DisplayName("POST with a missing interval is rejected")
	void createMissingInterval() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Blog\", \"url\": \"https://blog.example.com\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.checkIntervalSeconds").exists());
	}

	@Test
	@DisplayName("POST with malformed JSON: 400")
	void createMalformedJson() throws Exception {
		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("POST with an invalid URL (e.g. credentials): 400 with the reason")
	void createInvalidUrl() throws Exception {
		when(service.create(any(SiteRequest.class)))
				.thenThrow(new InvalidSiteUrlException("URL must not contain credentials"));

		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("URL must not contain credentials"));
	}

	@Test
	@DisplayName("POST with a host that resolves to a private address: 400")
	void createUnsafeHost() throws Exception {
		when(service.create(any(SiteRequest.class))).thenThrow(new UnsafeHostException("internal.example.com"));

		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("internal.example.com")));
	}

	@Test
	@DisplayName("POST with an already registered URL: 409")
	void createDuplicate() throws Exception {
		when(service.create(any(SiteRequest.class)))
				.thenThrow(new DuplicateSiteException("https://blog.example.com"));

		mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("GET /api/sites lists the sites")
	void list() throws Exception {
		when(service.findAll()).thenReturn(List.of(response(1), response(2)));

		mvc.perform(get("/api/sites"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(1));
	}

	@Test
	@DisplayName("GET /api/sites/{id} returns one site")
	void getOne() throws Exception {
		when(service.findById(1L)).thenReturn(response(1));

		mvc.perform(get("/api/sites/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Blog"));
	}

	@Test
	@DisplayName("GET /api/sites/{id} for an unknown site: 404 problem+json")
	void getUnknown() throws Exception {
		when(service.findById(99L)).thenThrow(new SiteNotFoundException(99L));

		mvc.perform(get("/api/sites/99"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("GET /api/sites/{id} with a non-numeric id: 400")
	void getWithBadId() throws Exception {
		mvc.perform(get("/api/sites/abc")).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PUT /api/sites/{id} updates a site")
	void update() throws Exception {
		when(service.update(eq(1L), any(SiteRequest.class))).thenReturn(response(1));

		mvc.perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	@DisplayName("PUT /api/sites/{id} validates the body too")
	void updateValidation() throws Exception {
		mvc.perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Blog\", \"url\": \"https://blog.example.com\", \"checkIntervalSeconds\": 29}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.checkIntervalSeconds").exists());
	}

	@Test
	@DisplayName("DELETE /api/sites/{id} returns 204")
	void deleteReturns204() throws Exception {
		mvc.perform(delete("/api/sites/1")).andExpect(status().isNoContent());

		verify(service).delete(1L);
	}

	@Test
	@DisplayName("DELETE /api/sites/{id} for an unknown site: 404")
	void deleteUnknown() throws Exception {
		doThrow(new SiteNotFoundException(99L)).when(service).delete(99L);

		mvc.perform(delete("/api/sites/99")).andExpect(status().isNotFound());
	}
}
