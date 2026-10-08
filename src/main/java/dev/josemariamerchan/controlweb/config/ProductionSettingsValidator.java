package dev.josemariamerchan.controlweb.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fail fast in production: with the {@code prod} profile active the application refuses to start if any setting is
 * only acceptable on a developer's machine. Without the profile nothing here applies.
 *
 * <p>Every problem is reported at once (so they can be fixed in one go) and by NAME only: several of these settings are
 * secrets, and an error message ends up in logs. Runs after the configuration files are loaded, like
 * {@link SecuritySettingsValidator}; registered in {@code META-INF/spring.factories}.
 */
public class ProductionSettingsValidator implements EnvironmentPostProcessor, Ordered {

	static final String PROFILE = "prod";

	/** Values that appear in .env.example or in tutorials; a real deployment must never use them. */
	private static final Set<String> PLACEHOLDERS = Set.of("change-me", "changeme", "change_me", "password", "secret",
			"admin", "postgres", "controlweb", "example", "test");

	private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127(\\.\\d{1,3}){3}");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.acceptsProfiles(Profiles.of(PROFILE))) {
			return;
		}
		List<String> problems = violations(environment);
		if (!problems.isEmpty()) {
			throw new InvalidConfigurationException(
					"The production profile (prod) refuses to start: " + String.join(" ", problems),
					"Fix the settings named above (production values go in the .env.prod file on the server; see docs/DESPLIEGUE.md). "
							+ "The values themselves are never printed on purpose.");
		}
	}

	static List<String> violations(Environment environment) {
		Binder binder = Binder.get(environment);
		List<String> problems = new ArrayList<>();

		if (list(binder, "app.proxy.trusted-addresses").isEmpty()) {
			problems.add("TRUSTED_PROXY_ADDRESSES is empty: without the exact IP of the reverse proxy every client looks like "
					+ "the proxy, and a few failed logins would lock everybody out.");
		}
		if (environment.getProperty("app.checker.allow-private-hosts", Boolean.class, false)) {
			problems.add("app.checker.allow-private-hosts is true, which switches the SSRF guard off.");
		}
		// springdoc is ON unless told otherwise, so "not explicitly disabled" counts as enabled.
		if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)
				|| environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true)) {
			problems.add("API documentation (springdoc api-docs / Swagger UI) is enabled or not explicitly disabled.");
		}
		if (looserThanProduction(binder.bindOrCreate("app.login-rate-limit", LoginRateLimitProperties.class))) {
			problems.add("A login rate limit is looser than the production limit (more tokens or a faster refill): "
					+ "development or test limits must not reach production.");
		}
		if (unsafeOrigins(list(binder, "app.cors.allowed-origins"))) {
			problems.add("CORS_ALLOWED_ORIGINS contains a plain http:// or a localhost origin.");
		}
		if (unsafeOrigins(list(binder, "app.public.cors-allowed-origins"))) {
			problems.add("PUBLIC_CORS_ALLOWED_ORIGINS contains a plain http:// or a localhost origin.");
		}
		secret(problems, "JWT_SECRET", environment.getProperty("app.jwt.secret"), true);
		secret(problems, "ADMIN_PASSWORD", environment.getProperty("app.admin.password"), false);
		secret(problems, "DB_PASSWORD", environment.getProperty("spring.datasource.password"), false);

		String heartbeat = environment.getProperty("app.heartbeat.url", "").strip();
		if (!heartbeat.isEmpty() && !heartbeat.toLowerCase(Locale.ROOT).startsWith("https://")) {
			problems.add("HEARTBEAT_URL is set but is not an https URL.");
		}
		return problems;
	}

	/** Is this one of the example values (ignoring case and surrounding spaces)? */
	static boolean isPlaceholder(String value) {
		return value == null || PLACEHOLDERS.contains(value.strip().toLowerCase(Locale.ROOT));
	}

	private static void secret(List<String> problems, String variable, String value, boolean base64Key) {
		if (value == null || value.isBlank()) {
			problems.add(variable + " is empty.");
		}
		else if (isPlaceholder(value) || (base64Key && isRepeatedByte(value))) {
			problems.add(variable + " is an example value from .env.example (or a trivial one): generate a real one.");
		}
	}

	/** A key of one repeated byte (32 zero bytes, for instance) is not random. */
	private static boolean isRepeatedByte(String base64) {
		try {
			byte[] bytes = Base64.getDecoder().decode(base64.strip());
			if (bytes.length == 0) {
				return false;
			}
			for (byte b : bytes) {
				if (b != bytes[0]) {
					return false;
				}
			}
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean looserThanProduction(LoginRateLimitProperties actual) {
		LoginRateLimitProperties ceiling = LoginRateLimitProperties.defaults();
		return actual.ipAttempts() > ceiling.ipAttempts()
				|| actual.ipAttemptsRefill().compareTo(ceiling.ipAttemptsRefill()) < 0
				|| actual.ipFailures() > ceiling.ipFailures()
				|| actual.ipFailuresRefill().compareTo(ceiling.ipFailuresRefill()) < 0
				|| actual.accountFailures() > ceiling.accountFailures()
				|| actual.accountFailuresRefill().compareTo(ceiling.accountFailuresRefill()) < 0
				|| actual.globalAttempts() > ceiling.globalAttempts()
				|| actual.globalAttemptsRefill().compareTo(ceiling.globalAttemptsRefill()) < 0;
	}

	private static boolean unsafeOrigins(List<String> origins) {
		for (String origin : origins) {
			String value = origin.strip().toLowerCase(Locale.ROOT);
			if (value.isEmpty()) {
				continue;
			}
			if (value.startsWith("http://")) {
				return true;
			}
			String host = hostOf(value);
			if (host != null && (host.equals("localhost") || host.endsWith(".localhost") || host.equals("[::1]")
					|| host.equals("0.0.0.0") || LOOPBACK_IPV4.matcher(host).matches())) {
				return true;
			}
		}
		return false;
	}

	private static String hostOf(String origin) {
		try {
			return URI.create(origin).getHost();
		}
		catch (IllegalArgumentException ex) {
			return null; // the format is the business of CorsProperties
		}
	}

	private static List<String> list(Binder binder, String name) {
		return binder.bind(name, Bindable.listOf(String.class)).orElse(List.of()).stream()
				.map(String::strip).filter(entry -> !entry.isEmpty()).toList();
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
