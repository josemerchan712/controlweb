package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Replaces the usual wall of stack trace with Spring Boot's "APPLICATION FAILED TO START" block
 * (description + action) when a setting is missing, unsafe or out of range (security settings, retention...). Registered in
 * {@code META-INF/spring.factories}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE) // before Spring Boot's generic "Failed to bind properties" analyzer
public class InvalidConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidConfigurationException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
		return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
	}
}
