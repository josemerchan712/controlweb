package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/**
 * How long check history is kept. Incidents are never deleted. The application does not start with fewer than
 * {@value #MIN_DAYS} days: the statistics and the public uptime look back 30 days, so a shorter retention would
 * silently falsify them.
 *
 * @param enabled   turns the daily purge on or off
 * @param days      checks strictly older than this many days are deleted (env var {@code RETENTION_DAYS})
 * @param cron      when the purge runs (Spring six-field cron, server time); default 03:30
 * @param batchSize rows deleted per transaction, so a big backlog never holds one long lock
 */
@ConfigurationProperties("app.retention")
public record RetentionProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("90") int days,
		@DefaultValue("0 30 3 * * *") String cron,
		@DefaultValue("5000") int batchSize) {

	public static final int MIN_DAYS = 30;
	public static final int MIN_BATCH_SIZE = 100;
	public static final int MAX_BATCH_SIZE = 50_000;

	public RetentionProperties {
		if (days < MIN_DAYS) {
			throw new InvalidConfigurationException(
					"RETENTION_DAYS is %d, but at least %d days are required (statistics and the public uptime look back 30 days)."
							.formatted(days, MIN_DAYS),
					"Set RETENTION_DAYS (environment or .env) to %d or more; the default is 90.".formatted(MIN_DAYS));
		}
		if (cron == null || !CronExpression.isValidExpression(cron)) {
			throw new InvalidConfigurationException("app.retention.cron is not a valid cron expression.",
					"Use a six-field Spring cron, for example \"0 30 3 * * *\" (every day at 03:30).");
		}
		if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
			throw new InvalidConfigurationException("app.retention.batch-size must be between %d and %d."
					.formatted(MIN_BATCH_SIZE, MAX_BATCH_SIZE),
					"Set app.retention.batch-size to a value in that range; the default is 5000.");
		}
	}
}
