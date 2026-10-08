package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Replaces the usual wall of stack trace with Spring Boot's "APPLICATION FAILED TO START" block
 * (description + action) when a security setting is missing or weak. Registered in
 * {@code META-INF/spring.factories}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE) // before Spring Boot's generic "Failed to bind properties" analyzer
public class InvalidSecurityConfigurationFailureAnalyzer
		extends AbstractFailureAnalyzer<InvalidSecurityConfigurationException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, InvalidSecurityConfigurationException cause) {
		return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
	}
}
