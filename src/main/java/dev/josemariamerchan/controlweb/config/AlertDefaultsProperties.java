package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Thresholds of the alert rules created automatically for every new site.
 *
 * @param downConsecutiveFailures failed checks in a row before a DOWN incident is opened
 * @param highLatencyMs           latency in milliseconds above which a HIGH_LATENCY incident is opened
 * @param sslExpiryDays           days left on the certificate below which an SSL_EXPIRING incident is opened
 */
@ConfigurationProperties("app.alert-defaults")
public record AlertDefaultsProperties(
		@DefaultValue("2") int downConsecutiveFailures,
		@DefaultValue("2000") int highLatencyMs,
		@DefaultValue("14") int sslExpiryDays) {
}
