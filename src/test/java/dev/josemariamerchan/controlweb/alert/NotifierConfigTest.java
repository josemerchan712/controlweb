package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.TestCredentials;
import dev.josemariamerchan.controlweb.alert.telegram.TelegramNotifier;
import dev.josemariamerchan.controlweb.config.TelegramProperties;

/** Telegram is used only when fully configured; a half-done setup is announced clearly at startup. */
class NotifierConfigTest {

	private static final String TOKEN = TestCredentials.TELEGRAM_TOKEN;
	private static final String CHAT_ID = "-1001234567890";

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(TelegramProperties.class)
	@Import(NotifierConfig.class)
	static class Wiring {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

	private static List<ILoggingEvent> warnings(LogCapture logs) {
		return logs.events().stream().filter(event -> event.getLevel() == Level.WARN).toList();
	}

	@Test
	@DisplayName("with token and chat id the notifier is the Telegram one")
	void bothSet() {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.withPropertyValues("app.telegram.bot-token=" + TOKEN, "app.telegram.chat-id=" + CHAT_ID).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Notifier.class)).isInstanceOf(TelegramNotifier.class);
			});
			assertThat(warnings(logs)).isEmpty();
			assertThat(logs.messages()).anyMatch(message -> message.contains("enabled"));
		}
	}

	@Test
	@DisplayName("with neither variable, alerts go to the log, quietly (not an error, not a warning)")
	void neitherSet() {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.run(context -> assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class));
			assertThat(warnings(logs)).isEmpty();
			assertThat(logs.messages()).singleElement().asString().contains("not configured").contains("written to the log");
		}
	}

	@Test
	@DisplayName("blank values (an empty .env line) count as not set")
	void blankValuesAreNotSet() {
		runner.withPropertyValues("app.telegram.bot-token=", "app.telegram.chat-id=   ")
				.run(context -> assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class));
	}

	@Test
	@DisplayName("only the token: a clear warning names both variables, the notifier is the logging one, no value is printed")
	void onlyToken() {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.withPropertyValues("app.telegram.bot-token=" + TOKEN).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class);
			});
			assertThat(warnings(logs)).singleElement().satisfies(event -> {
				assertThat(event.getFormattedMessage()).contains("TELEGRAM_BOT_TOKEN is set but TELEGRAM_CHAT_ID is missing")
						.contains("DISABLED").doesNotContain(TOKEN);
			});
			assertThat(String.join("\n", logs.fullText())).doesNotContain(TOKEN);
		}
	}

	@Test
	@DisplayName("only the chat id: a clear warning, the logging notifier, no value printed")
	void onlyChatId() {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.withPropertyValues("app.telegram.chat-id=" + CHAT_ID).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class);
			});
			assertThat(warnings(logs)).singleElement().satisfies(event -> {
				assertThat(event.getFormattedMessage()).contains("TELEGRAM_CHAT_ID is set but TELEGRAM_BOT_TOKEN is missing")
						.doesNotContain(CHAT_ID);
			});
		}
	}

	@ParameterizedTest(name = "token \"{0}\" is refused with a warning")
	@ValueSource(strings = { "not-a-token", "12345", "abc:def", "123456789:short", "123456789:has space in it xxxxxxxxxxxx",
			"123456789:slash/in/the/middle/xxxxxxxxxxxx" })
	void malformedToken(String badToken) {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.withPropertyValues("app.telegram.bot-token=" + badToken.replace(" ", "\\ "), "app.telegram.chat-id=" + CHAT_ID)
					.run(context -> assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class));
			assertThat(warnings(logs)).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
					.contains("TELEGRAM_BOT_TOKEN does not look like a bot token").doesNotContain(badToken));
		}
	}

	@ParameterizedTest(name = "chat id \"{0}\" is refused with a warning")
	@ValueSource(strings = { "abc", "12 34", "@x", "https://t.me/chat", "1.5" })
	void malformedChatId(String badChatId) {
		try (LogCapture logs = LogCapture.of(NotifierConfig.class, Level.DEBUG)) {
			runner.withPropertyValues("app.telegram.bot-token=" + TOKEN, "app.telegram.chat-id=" + badChatId.replace(" ", "\\ "))
					.run(context -> assertThat(context.getBean(Notifier.class)).isInstanceOf(LoggingNotifier.class));
			assertThat(warnings(logs)).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
					.contains("TELEGRAM_CHAT_ID does not look like a chat id").doesNotContain(badChatId));
		}
	}

	@ParameterizedTest(name = "chat id \"{0}\" is accepted")
	@ValueSource(strings = { "123456789", "-1001234567890", "@my_channel", "0" })
	void validChatIds(String chatId) {
		runner.withPropertyValues("app.telegram.bot-token=" + TOKEN, "app.telegram.chat-id=" + chatId)
				.run(context -> assertThat(context.getBean(Notifier.class)).isInstanceOf(TelegramNotifier.class));
	}

	// ---- TelegramProperties ----

	@Test
	@DisplayName("defaults: 3 s to connect, 5 s to read, 2 retries, 10 s max retry_after, the official API")
	void propertyDefaults() {
		runner.run(context -> {
			TelegramProperties properties = context.getBean(TelegramProperties.class);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.maxRetries()).isEqualTo(2);
			assertThat(properties.maxRetryAfter()).isEqualTo(Duration.ofSeconds(10));
			assertThat(properties.apiBaseUrl()).isEqualTo("https://api.telegram.org");
		});
	}

	@Test
	@DisplayName("timeouts and retries are configurable")
	void propertyOverrides() {
		runner.withPropertyValues("app.telegram.connect-timeout=1s", "app.telegram.read-timeout=2500ms",
				"app.telegram.max-retries=1", "app.telegram.retry-delay=100ms", "app.telegram.max-retry-after=4s").run(context -> {
					TelegramProperties properties = context.getBean(TelegramProperties.class);
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
					assertThat(properties.readTimeout()).isEqualTo(Duration.ofMillis(2500));
					assertThat(properties.maxRetries()).isEqualTo(1);
					assertThat(properties.retryDelay()).isEqualTo(Duration.ofMillis(100));
					assertThat(properties.maxRetryAfter()).isEqualTo(Duration.ofSeconds(4));
				});
	}

	@ParameterizedTest(name = "{0} is rejected")
	@ValueSource(strings = { "app.telegram.connect-timeout=0s", "app.telegram.read-timeout=-1s", "app.telegram.max-retries=-1",
			"app.telegram.max-retries=6", "app.telegram.retry-delay=11s", "app.telegram.max-retry-after=-1s",
			"app.telegram.api-base-url=http://api.telegram.org", "app.telegram.api-base-url=ftp://example.com",
			"app.telegram.api-base-url=not-a-url" })
	void invalidSettingsAreRejected(String setting) {
		runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("the token travels only to an https address (plain http is accepted for loopback, used by tests)")
	void apiBaseUrlMustBeHttps() {
		runner.withPropertyValues("app.telegram.api-base-url=https://api.telegram.org/")
				.run(context -> assertThat(context.getBean(TelegramProperties.class).apiBaseUrl()).isEqualTo("https://api.telegram.org"));
		runner.withPropertyValues("app.telegram.api-base-url=http://localhost:8080")
				.run(context -> assertThat(context).hasNotFailed());
	}
}
