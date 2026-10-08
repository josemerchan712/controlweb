package dev.josemariamerchan.controlweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;

/** Registers the framework-free {@link RuleEngine} as a bean (the engine itself knows nothing about Spring). */
@Configuration(proxyBeanMethods = false)
public class RuleEngineConfig {

	@Bean
	public RuleEngine ruleEngine(RuleProperties properties) {
		return new RuleEngine(properties.latencySamples());
	}
}
