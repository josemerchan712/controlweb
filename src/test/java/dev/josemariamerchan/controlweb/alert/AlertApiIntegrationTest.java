package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.josemariamerchan.controlweb.RecordingNotifier;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.TestTokens;
import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.alert.service.AlertTestRateLimiter;

/** POST /api/alerts/test: authenticated, honest about failures, safe to expose, and rate limited. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlertApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RecordingNotifier notifier;

	@Autowired
	private AlertTestRateLimiter limiter;

	@BeforeEach
	void reset() {
		notifier.reset();
		limiter.reset();
	}

	@Test
	@DisplayName("a delivered test message is a 200 and the notifier received the Spanish test text")
	void delivered() throws Exception {
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.channel").value("telegram"))
				.andExpect(jsonPath("$.sentAt").isNotEmpty());

		assertThat(notifier.messages()).singleElement().asString().contains("Mensaje de prueba");
	}

	@Test
	@DisplayName("Telegram not configured: 503 problem+json that says how to fix it")
	void notConfigured() throws Exception {
		notifier.respondWith(SendResult.notConfigured());

		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.detail").value(containsString("not configured")))
				.andExpect(jsonPath("$.detail").value(containsString("TELEGRAM_BOT_TOKEN")))
				.andExpect(jsonPath("$.detail").value(containsString("TELEGRAM_CHAT_ID")));
	}

	@Test
	@DisplayName("Telegram failing: 503 problem+json with the safe reason, no token, no URL")
	void failed() throws Exception {
		notifier.respondWith(SendResult.failed("Telegram rejected the alert (HTTP 401): check TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID"));

		String body = mvc.perform(post("/api/alerts/test").with(TestTokens.bearer()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value(containsString("could not be delivered")))
				.andExpect(jsonPath("$.detail").value(containsString("HTTP 401")))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(TestCredentials.TELEGRAM_TOKEN).doesNotContain("api.telegram.org").doesNotContain("http");
	}

	@Test
	@DisplayName("even if a failure detail somehow carried a bot token, the response masks it")
	void detailsAreSanitized() throws Exception {
		notifier.respondWith(SendResult.failed("failed calling https://api.telegram.org/bot" + TestCredentials.TELEGRAM_TOKEN
				+ "/sendMessage?x=1"));

		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value(not(containsString(TestCredentials.TELEGRAM_TOKEN))))
				.andExpect(jsonPath("$.detail").value(not(containsString("x=1"))));
	}

	@Test
	@DisplayName("a second call within 10 seconds is a 429 with Retry-After; nothing more is sent")
	void rateLimited() throws Exception {
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isOk());

		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer()))
				.andExpect(status().isTooManyRequests())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.status").value(429));
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isTooManyRequests());

		assertThat(notifier.messages()).as("the limit protects Telegram from being spammed").hasSize(1);
	}

	@Test
	@DisplayName("failed attempts count towards the limit too")
	void failuresCountTowardsTheLimit() throws Exception {
		notifier.respondWith(SendResult.failed("Timed out talking to Telegram"));

		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isServiceUnavailable());
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isTooManyRequests());

		assertThat(notifier.messages()).hasSize(1);
	}

	@Test
	@DisplayName("after the limiter is released the endpoint works again")
	void worksAgainAfterTheInterval() throws Exception {
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isOk());
		limiter.reset(); // stands for "10 seconds later"

		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("it requires a token: without one nothing is sent and the limiter is not consumed")
	void requiresAuthentication() throws Exception {
		mvc.perform(post("/api/alerts/test")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/alerts/test").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());

		assertThat(notifier.messages()).isEmpty();
		mvc.perform(post("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("only POST is accepted")
	void onlyPost() throws Exception {
		mvc.perform(get("/api/alerts/test").with(TestTokens.bearer())).andExpect(status().isMethodNotAllowed());
	}
}
