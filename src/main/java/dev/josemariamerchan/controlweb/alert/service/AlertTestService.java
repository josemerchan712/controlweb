package dev.josemariamerchan.controlweb.alert.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import dev.josemariamerchan.controlweb.alert.AlertMessageFormatter;
import dev.josemariamerchan.controlweb.alert.Notifier;
import dev.josemariamerchan.controlweb.alert.SendResult;

/**
 * Sends a test message through the configured notifier and reports the outcome. Unlike real alerts it is
 * synchronous, because the caller wants to know whether it worked.
 */
@Service
public class AlertTestService {

	public record TestAlertResult(String channel, Instant sentAt) {
	}

	private final Notifier notifier;
	private final AlertMessageFormatter formatter;
	private final AlertTestRateLimiter limiter;
	private final Clock clock;

	public AlertTestService(Notifier notifier, AlertMessageFormatter formatter, AlertTestRateLimiter limiter, Clock clock) {
		this.notifier = notifier;
		this.formatter = formatter;
		this.limiter = limiter;
		this.clock = clock;
	}

	/**
	 * @throws AlertRateLimitedException if called again too soon
	 * @throws AlertDeliveryException    if Telegram is not configured or the message could not be sent
	 */
	public TestAlertResult sendTestAlert() {
		limiter.acquire();
		SendResult result = notifier.send(formatter.test());
		return switch (result.status()) {
			case SENT -> new TestAlertResult("telegram", clock.instant());
			case NOT_CONFIGURED -> throw new AlertDeliveryException(
					"Telegram is not configured: set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID");
			case FAILED -> throw new AlertDeliveryException(
					"The test message could not be delivered to Telegram: " + result.detail());
		};
	}
}
