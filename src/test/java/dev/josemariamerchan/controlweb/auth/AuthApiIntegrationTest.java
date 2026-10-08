package dev.josemariamerchan.controlweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.auth.user.Role;
import dev.josemariamerchan.controlweb.auth.user.User;
import dev.josemariamerchan.controlweb.auth.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthApiIntegrationTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private static String loginBody(String email, String password) {
		return "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password);
	}

	private String loginAndGetToken() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
	}

	// ---- seeding ----

	@Test
	@DisplayName("the admin from the environment is created at startup with a BCrypt hash")
	void adminIsSeeded() {
		User admin = users.findByEmail(TestCredentials.ADMIN_EMAIL).orElseThrow();

		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.getPasswordHash()).startsWith("$2").doesNotContain(TestCredentials.ADMIN_PASSWORD);
		assertThat(passwordEncoder.matches(TestCredentials.ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
	}

	// ---- login ----

	@Test
	@DisplayName("login with the right credentials returns a token that opens the private API")
	void loginReturnsUsableToken() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(TestCredentials.ADMIN_EMAIL, TestCredentials.ADMIN_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty());

		String token = loginAndGetToken();

		mvc.perform(get("/api/sites").with(TestTokens.bearer(token))).andExpect(status().isOk());
		mvc.perform(get("/api/auth/me").with(TestTokens.bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(TestCredentials.ADMIN_EMAIL))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	@DisplayName("the login email is case-insensitive")
	void loginEmailIsCaseInsensitive() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(TestCredentials.ADMIN_EMAIL.toUpperCase(), TestCredentials.ADMIN_PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("no user enumeration: wrong password and unknown email give the IDENTICAL 401 response")
	void sameResponseForWrongPasswordAndUnknownEmail() throws Exception {
		var wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(TestCredentials.ADMIN_EMAIL, "definitely-the-wrong-password")))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse();
		var unknownEmail = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("nobody@test.local", "definitely-the-wrong-password")))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse();

		assertThat(unknownEmail.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
		assertThat(unknownEmail.getContentType()).isEqualTo(wrongPassword.getContentType());
		assertThat(unknownEmail.getHeaderNames()).containsExactlyInAnyOrderElementsOf(wrongPassword.getHeaderNames());
		assertThat(wrongPassword.getContentAsString()).contains("Invalid email or password");
	}

	@Test
	@DisplayName("login with a malformed body is a 400 (not a 500)")
	void loginValidation() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("not-an-email", "whatever-password")))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("neither passwords nor tokens are written to the logs")
	void noSecretsInLogs(CapturedOutput output) throws Exception {
		// Only look at what is written during this test (a failing test elsewhere dumps its request, headers included).
		int alreadyCaptured = output.getAll().length();
		String token = loginAndGetToken();
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(TestCredentials.ADMIN_EMAIL, "a-wrong-password-in-the-log-test")));
		mvc.perform(get("/api/sites").header("Authorization", "Bearer " + token));

		assertThat(output.getAll().substring(alreadyCaptured))
				.doesNotContain(TestCredentials.ADMIN_PASSWORD)
				.doesNotContain("a-wrong-password-in-the-log-test")
				.doesNotContain(token)
				.doesNotContain(TestCredentials.JWT_SECRET);
	}

	// ---- actuator ----

	@Test
	@DisplayName("/actuator/health is public and reveals only the status")
	void healthIsPublicWithoutDetails() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@DisplayName("no other actuator endpoint is exposed, even with a valid token, and none is public")
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		for (String path : new String[] { "/actuator", "/actuator/env", "/actuator/info", "/actuator/beans",
				"/actuator/metrics", "/actuator/heapdump" }) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
			mvc.perform(get(path).with(TestTokens.bearer())).andExpect(status().isNotFound());
		}
	}

	// ---- swagger (only public in the dev profile) ----

	@Test
	@DisplayName("Swagger UI and the OpenAPI document are not public outside the dev profile")
	void swaggerIsNotPublicOutsideDev() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	// ---- CORS ----

	@Test
	@DisplayName("CORS preflight from a configured origin is allowed, echoing that exact origin (never a wildcard)")
	void corsAllowsConfiguredOrigin() throws Exception {
		mvc.perform(options("/api/sites")
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Origin", not("*")))
				.andExpect(header().string("Access-Control-Allow-Credentials", nullValue()));
	}

	@Test
	@DisplayName("CORS preflight from any other origin is refused")
	void corsRejectsOtherOrigins() throws Exception {
		// (not plain http://localhost: MockMvc requests are served from there, so that would be same-origin)
		for (String origin : new String[] { "https://evil.example.com", "http://localhost:5174", "https://localhost:5173" }) {
			mvc.perform(options("/api/sites")
					.header("Origin", origin)
					.header("Access-Control-Request-Method", "GET"))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		}
	}

	@Test
	@DisplayName("a real request from the configured origin carries the CORS header; the token is still required")
	void corsOnRealRequest() throws Exception {
		mvc.perform(get("/api/sites").header("Origin", ALLOWED_ORIGIN).with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));

		mvc.perform(get("/api/sites").header("Origin", ALLOWED_ORIGIN)).andExpect(status().isUnauthorized());
	}
}
