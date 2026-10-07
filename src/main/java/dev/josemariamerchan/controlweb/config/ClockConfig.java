package dev.josemariamerchan.controlweb.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	/** Injected wherever "now" is needed, so tests can fix the time. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
