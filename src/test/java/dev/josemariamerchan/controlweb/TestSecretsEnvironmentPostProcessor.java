package dev.josemariamerchan.controlweb;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Registered in {@code META-INF/spring.factories} (test classpath). Makes every Spring test hermetic:
 *
 * <ol>
 * <li>It removes the property source loaded from the developer's {@code .env} file. The application imports
 * that file on purpose ({@code spring.config.import}), but a test must never depend on, or be able to use,
 * real secrets: with a real Telegram token or JWT secret in {@code .env}, a test could silently start
 * with them (or message a real chat).</li>
 * <li>Unless {@code test.secrets=false}, it gives the test the random {@link TestCredentials}, with higher
 * priority than anything else. Setting {@code test.secrets=false} starts an application WITHOUT secrets
 * (used to test the startup failures).</li>
 * </ol>
 *
 * It runs just before the production {@code SecuritySettingsValidator}, so the validation sees the final values.
 */
public class TestSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	/** How Spring Boot names the property source created by {@code optional:file:.env[.properties]}. */
	private static final Pattern DOT_ENV_SOURCE = Pattern.compile("\\[\\.env\\]|\\.env\\[\\.properties\\]");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		List<String> dotEnvSources = environment.getPropertySources().stream()
				.map(PropertySource::getName)
				.filter(name -> DOT_ENV_SOURCE.matcher(name).find())
				.toList();
		dotEnvSources.forEach(environment.getPropertySources()::remove);

		if (environment.getProperty("test.secrets", Boolean.class, true)) {
			environment.getPropertySources().addFirst(new MapPropertySource("testSecrets", TestCredentials.asProperties()));
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}
}
