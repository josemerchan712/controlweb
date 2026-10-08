package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import dev.josemariamerchan.controlweb.auth.config.AdminProperties;
import dev.josemariamerchan.controlweb.auth.config.JwtProperties;

/**
 * Fails fast: validates the security settings as soon as the environment is ready, before any bean
 * exists. Otherwise an unrelated failure (e.g. the database being down) could hit first and hide
 * the real problem, or Flyway could migrate a database for an instance that is misconfigured.
 *
 * <p>Binding the same {@code @ConfigurationProperties} records runs their validating constructors.
 * Registered in {@code META-INF/spring.factories}; it must run after the config files are loaded.
 */
public class SecuritySettingsValidator implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Binder binder = Binder.get(environment);
		binder.bindOrCreate("app.jwt", JwtProperties.class);
		binder.bindOrCreate("app.admin", AdminProperties.class);
		binder.bindOrCreate("app.cors", CorsProperties.class);
		binder.bindOrCreate("app.public", PublicProperties.class);
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
