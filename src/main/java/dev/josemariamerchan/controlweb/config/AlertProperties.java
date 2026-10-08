package dev.josemariamerchan.controlweb.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How alerts are dispatched.
 *
 * @param queueCapacity   alerts waiting to be sent; when it is full new alerts are dropped (and logged)
 *                        instead of blocking the check that raised them
 * @param shutdownTimeout how long to let queued alerts drain when the application stops
 * @param testInterval    minimum time between two calls of {@code POST /api/alerts/test}
 */
@ConfigurationProperties("app.alerts")
public record AlertProperties(
		@DefaultValue("100") int queueCapacity,
		@DefaultValue("10s") Duration shutdownTimeout,
		@DefaultValue("10s") Duration testInterval) {

	public AlertProperties {
		if (queueCapacity < 1 || queueCapacity > 10_000) {
			throw new IllegalArgumentException("app.alerts.queue-capacity must be between 1 and 10000");
		}
		if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("app.alerts.shutdown-timeout must not be negative");
		}
		if (testInterval == null || testInterval.isNegative()) {
			throw new IllegalArgumentException("app.alerts.test-interval must not be negative");
		}
	}
}
