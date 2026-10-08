package dev.josemariamerchan.controlweb.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the periodic checker.
 *
 * @param enabled         turns the periodic cycle on or off (off in tests); manual checks always work
 * @param initialDelay    wait after startup before the first cycle
 * @param fixedDelay      pause between the END of one cycle and the start of the next
 * @param maxConcurrency  checks running at the same time (protects RAM, sockets and the monitored servers)
 * @param shutdownTimeout how long to wait for running checks when the application stops
 */
@ConfigurationProperties("app.scheduler")
public record SchedulerProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("5s") Duration initialDelay,
		@DefaultValue("10s") Duration fixedDelay,
		@DefaultValue("10") int maxConcurrency,
		@DefaultValue("10s") Duration shutdownTimeout) {

	public SchedulerProperties {
		if (initialDelay == null || initialDelay.isNegative()) {
			throw new IllegalArgumentException("app.scheduler.initial-delay must not be negative");
		}
		if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
			throw new IllegalArgumentException("app.scheduler.fixed-delay must be positive");
		}
		if (maxConcurrency < 1 || maxConcurrency > 200) {
			throw new IllegalArgumentException("app.scheduler.max-concurrency must be between 1 and 200");
		}
		if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("app.scheduler.shutdown-timeout must not be negative");
		}
	}
}
