package dev.josemariamerchan.controlweb.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.alert.telegram.TelegramNotifier;
import dev.josemariamerchan.controlweb.config.TelegramProperties;

/**
 * Chooses the notifier: Telegram only when BOTH {@code TELEGRAM_BOT_TOKEN} and {@code TELEGRAM_CHAT_ID}
 * are set (and look valid); otherwise alerts are only logged. When the configuration is half-done, a
 * clear warning at startup says which variable is missing. Values are never printed, only variable names.
 */
@Configuration(proxyBeanMethods = false)
public class NotifierConfig {

	private static final Logger log = LoggerFactory.getLogger(NotifierConfig.class);

	@Bean
	public Notifier notifier(TelegramProperties properties) {
		if (!properties.hasToken() && !properties.hasChatId()) {
			log.info("Telegram alerts are not configured (TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are not set): "
					+ "alerts will only be written to the log");
			return new LoggingNotifier();
		}
		if (properties.hasToken() && !properties.hasChatId()) {
			log.warn("Telegram alerts are DISABLED: TELEGRAM_BOT_TOKEN is set but TELEGRAM_CHAT_ID is missing. "
					+ "Set both to enable them; meanwhile alerts will only be written to the log");
			return new LoggingNotifier();
		}
		if (!properties.hasToken()) {
			log.warn("Telegram alerts are DISABLED: TELEGRAM_CHAT_ID is set but TELEGRAM_BOT_TOKEN is missing. "
					+ "Set both to enable them; meanwhile alerts will only be written to the log");
			return new LoggingNotifier();
		}
		if (!properties.tokenLooksValid()) {
			log.warn("Telegram alerts are DISABLED: TELEGRAM_BOT_TOKEN does not look like a bot token "
					+ "(expected <digits>:<letters, digits, '_' or '-'>); alerts will only be written to the log");
			return new LoggingNotifier();
		}
		if (!properties.chatIdLooksValid()) {
			log.warn("Telegram alerts are DISABLED: TELEGRAM_CHAT_ID does not look like a chat id "
					+ "(expected a number such as 123456789 or -1001234567890, or @channelname); "
					+ "alerts will only be written to the log");
			return new LoggingNotifier();
		}
		log.info("Telegram alerts are enabled");
		return TelegramNotifier.create(properties);
	}
}
