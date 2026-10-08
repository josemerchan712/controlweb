package dev.josemariamerchan.controlweb;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Registered in {@code META-INF/spring.factories} (test classpath). Gives every Spring test the
 * random {@link TestCredentials}, with higher priority than any environment variable or {@code .env}
 * file. It runs just before the production {@code SecuritySettingsValidator}, so the validation
 * sees these values. Set {@code test.secrets=false} to start an application WITHOUT them (used to
 * test the startup failures).
 */
public class TestSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.getProperty("test.secrets", Boolean.class, true)) {
			environment.getPropertySources().addFirst(new MapPropertySource("testSecrets", TestCredentials.asProperties()));
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}
}
