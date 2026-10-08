package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning of the rule engine.
 *
 * @param latencySamples how many consecutive SUCCESSFUL checks must all exceed the latency threshold
 *                       before a HIGH_LATENCY incident opens (so one spike never does)
 */
@ConfigurationProperties("app.rules")
public record RuleProperties(@DefaultValue("3") int latencySamples) {

	public RuleProperties {
		if (latencySamples < 1 || latencySamples > 20) {
			throw new IllegalArgumentException("app.rules.latency-samples must be between 1 and 20");
		}
	}
}
