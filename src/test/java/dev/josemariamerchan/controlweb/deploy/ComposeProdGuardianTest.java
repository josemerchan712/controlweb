package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import dev.josemariamerchan.controlweb.config.ConfigFilesGuardianTest;

/**
 * Static guardian of compose.prod.yml (no Docker needed): not one literal secret, required variables that stop
 * `docker compose` when missing, the hardening of the containers, and the network layout (never a Rotten Odds network,
 * only the application with a way out). What Docker really applied is checked with `docker inspect` (docs/DESPLIEGUE.md).
 */
class ComposeProdGuardianTest {

	private static final Path COMPOSE = Path.of("compose.prod.yml");
	private static final Pattern REQUIRED = Pattern.compile("\\$\\{([A-Z0-9_]+):\\?([^}]+)}");
	private static final Pattern OPTIONAL_EMPTY = Pattern.compile("\\$\\{([A-Z0-9_]+):-}");
	private static final Pattern OPTIONAL_DEFAULT = Pattern.compile("\\$\\{([A-Z0-9_]+):-([^}]+)}");
	private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|HEARTBEAT|CHAT_ID|USERNAME|EMAIL).*");

	private static final Set<String> REQUIRED_VARIABLES = Set.of("DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "ADMIN_EMAIL",
			"ADMIN_PASSWORD", "TRUSTED_PROXY_ADDRESSES", "PUBLIC_CORS_ALLOWED_ORIGINS");

	@SuppressWarnings("unchecked")
	private static Map<String, Object> compose() throws IOException {
		return (Map<String, Object>) new Yaml().load(Files.readString(COMPOSE));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> service(String name) throws IOException {
		return (Map<String, Object>) ((Map<String, Object>) compose().get("services")).get(name);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> environment(String service) throws IOException {
		return (Map<String, Object>) service(service).get("environment");
	}

	@SuppressWarnings("unchecked")
	private static List<String> list(Map<String, Object> service, String key) {
		Object value = service.get(key);
		return value == null ? List.of() : ((List<Object>) value).stream().map(String::valueOf).toList();
	}

	// ---- secrets ----

	@Test
	@DisplayName("no environment value is a literal secret: each one is a ${VARIABLE} reference, or the plain constant 'prod'")
	void environmentHasNoLiteralSecrets() throws IOException {
		for (String serviceName : List.of("controlweb-app", "controlweb-db")) {
			environment(serviceName).forEach((name, rawValue) -> {
				String value = String.valueOf(rawValue);
				boolean reference = value.contains("${");
				boolean constant = name.equals("SPRING_PROFILES_ACTIVE") && value.equals("prod");
				assertThat(reference || constant).as("%s.%s must be a ${VARIABLE} reference", serviceName, name).isTrue();
				assertThat(ConfigFilesGuardianTest.suspicion(name, value.replaceAll("\\$\\{[A-Z0-9_]+:[?-][^}]*}", Matcher.quoteReplacement("${REF:}"))))
						.as("%s.%s", serviceName, name).isNull();
			});
		}
	}

	@Test
	@DisplayName("the seven values a deployment cannot work without are REQUIRED (${X:?message}) in the application")
	void requiredVariablesStopCompose() throws IOException {
		Map<String, Object> environment = environment("controlweb-app");
		for (String variable : REQUIRED_VARIABLES) {
			String value = String.valueOf(environment.get(variable));
			Matcher matcher = REQUIRED.matcher(value);
			assertThat(matcher.matches()).as("%s must be ${%s:?message}, was %s", variable, variable, value).isTrue();
			assertThat(matcher.group(1)).isEqualTo(variable);
			assertThat(matcher.group(2).strip()).isNotEmpty();
		}
	}

	@Test
	@DisplayName("a secret-looking setting never has a non-empty default: it is required or defaults to empty")
	void secretsNeverHaveDefaults() throws IOException {
		for (String serviceName : List.of("controlweb-app", "controlweb-db")) {
			environment(serviceName).forEach((name, rawValue) -> {
				String value = String.valueOf(rawValue);
				if (SECRET_NAME.matcher(name).matches() && value.contains("${")) {
					boolean required = REQUIRED.matcher(value).matches();
					boolean emptyDefault = OPTIONAL_EMPTY.matcher(value).matches();
					assertThat(required || emptyDefault).as("%s.%s = %s", serviceName, name, value).isTrue();
				}
			});
		}
	}

	@Test
	@DisplayName("the database password and the connection string never appear together with credentials in the URL")
	void connectionUrlHasNoCredentials() throws IOException {
		String url = String.valueOf(environment("controlweb-app").get("DB_URL"));

		assertThat(url).startsWith("jdbc:postgresql://controlweb-db:5432/").doesNotContainPattern("//[^/]*@");
		assertThat(url).doesNotContain("password");
	}

	@Test
	@DisplayName("the stack takes its variables from --env-file: no env_file, and no value is read from a file in the repository")
	void noEnvFile() throws IOException {
		assertThat(service("controlweb-app")).doesNotContainKey("env_file");
		assertThat(service("controlweb-db")).doesNotContainKey("env_file");
	}

	// ---- hardening of the application ----

	@Test
	@DisplayName("application container: unprivileged, read-only, no capabilities, no new privileges, bounded")
	void applicationIsHardened() throws IOException {
		Map<String, Object> app = service("controlweb-app");

		assertThat(app.get("user")).isEqualTo("10001:10001");
		assertThat(app.get("read_only")).isEqualTo(true);
		assertThat(list(app, "cap_drop")).containsExactly("ALL");
		assertThat(list(app, "cap_add")).isEmpty();
		assertThat(list(app, "security_opt")).containsExactly("no-new-privileges:true");
		assertThat(list(app, "tmpfs")).hasSize(1).allMatch(mount -> mount.startsWith("/tmp:") && mount.contains("noexec")
				&& mount.contains("nosuid") && mount.contains("size="));
		assertThat((Integer) app.get("pids_limit")).isBetween(1, 512);
		assertThat(app.get("mem_limit")).isEqualTo("512m");
		assertThat(app.get("memswap_limit")).as("no swap for the application").isEqualTo(app.get("mem_limit"));
		assertThat(app.get("restart")).isEqualTo("unless-stopped");
	}

	@Test
	@DisplayName("nothing is privileged, shares the host, publishes a port or mounts a host path")
	void nothingExposesTheHost() throws IOException {
		for (String name : List.of("controlweb-app", "controlweb-db")) {
			Map<String, Object> service = service(name);
			assertThat(service).as(name).doesNotContainKeys("privileged", "network_mode", "pid", "ipc", "devices", "extra_hosts",
					"sysctls", "cgroup_parent", "uts", "userns_mode", "volumes_from");
			assertThat(service).as("%s publishes no port: Caddy reaches the application over the private network", name)
					.doesNotContainKey("ports");
			for (String volume : list(service, "volumes")) {
				assertThat(volume).as("%s volume", name).matches("^controlweb-pgdata:/var/lib/postgresql/data$");
			}
		}
	}

	@Test
	@DisplayName("both containers rotate their logs (3 files of 10 MB) with the json-file driver")
	void logsAreRotated() throws IOException {
		String text = Files.readString(COMPOSE);
		Map<String, Object> app = service("controlweb-app");
		Map<String, Object> db = service("controlweb-db");

		assertThat(text).contains("max-size: \"10m\"").contains("max-file: \"3\"").contains("driver: json-file");
		assertThat(app.get("logging")).isNotNull().isEqualTo(db.get("logging"));
	}

	// ---- database ----

	@Test
	@DisplayName("database container: pinned by digest, small, read-only, only the capabilities its entrypoint needs")
	void databaseIsHardened() throws IOException {
		Map<String, Object> db = service("controlweb-db");

		assertThat(String.valueOf(db.get("image"))).matches("postgres:16\\.\\d+-alpine3\\.\\d+@sha256:[0-9a-f]{64}");
		assertThat(db.get("read_only")).isEqualTo(true);
		assertThat(list(db, "cap_drop")).containsExactly("ALL");
		assertThat(list(db, "cap_add")).containsExactlyInAnyOrder("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID");
		assertThat(list(db, "security_opt")).containsExactly("no-new-privileges:true");
		assertThat((Integer) db.get("pids_limit")).isBetween(1, 200);
		assertThat(db.get("mem_limit")).isEqualTo("256m");
		assertThat(db.get("memswap_limit")).isEqualTo("256m");
		assertThat(db).containsKey("healthcheck");
	}

	// ---- networks ----

	@Test
	@DisplayName("the application is on exactly three networks, the database on its private one only")
	@SuppressWarnings("unchecked")
	void networkMembership() throws IOException {
		Map<String, Object> app = service("controlweb-app");
		Map<String, Object> db = service("controlweb-db");

		assertThat(((Map<String, Object>) app.get("networks")).keySet())
				.containsExactlyInAnyOrder("controlweb_proxy", "controlweb_db", "controlweb_egress");
		assertThat(list(db, "networks")).containsExactly("controlweb_db");
		Map<String, Object> proxy = (Map<String, Object>) ((Map<String, Object>) app.get("networks")).get("controlweb_proxy");
		assertThat(proxy.get("ipv4_address")).isEqualTo("10.77.0.20");
	}

	@Test
	@DisplayName("network definitions: proxy network external, database network internal, only egress can reach the internet")
	@SuppressWarnings("unchecked")
	void networkDefinitions() throws IOException {
		Map<String, Object> networks = (Map<String, Object>) compose().get("networks");
		Map<String, Object> proxy = (Map<String, Object>) networks.get("controlweb_proxy");
		Map<String, Object> database = (Map<String, Object>) networks.get("controlweb_db");
		Map<String, Object> egress = (Map<String, Object>) networks.get("controlweb_egress");

		assertThat(networks.keySet()).containsExactlyInAnyOrder("controlweb_proxy", "controlweb_db", "controlweb_egress");
		assertThat(proxy.get("external")).isEqualTo(true);
		assertThat(database.get("internal")).isEqualTo(true);
		assertThat(egress).doesNotContainKey("internal");
		assertThat(String.valueOf(database)).contains("cw-db").contains("10.77.1.0/24");
		assertThat(String.valueOf(egress)).contains("cw-egress").contains("10.77.2.0/24");
	}

	@Test
	@DisplayName("ControlWeb never joins a Rotten Odds network or uses its names, and its volume does not collide with the dev stack")
	void neverTouchesRottenOdds() throws IOException {
		String text = Files.readString(COMPOSE);

		assertThat(text).doesNotContainPattern("(?i)rottenodds_(internal|edge)");
		assertThat(text).doesNotContain("rottenodds").doesNotContain("caddy-data");
		assertThat(text).contains("name: controlweb_prod_pgdata").doesNotContain("name: controlweb_pgdata");
		for (String forbiddenName : List.of("api", "db", "caddy")) {
			assertThat(((Map<String, Object>) compose().get("services"))).doesNotContainKey(forbiddenName);
		}
	}

	@Test
	@DisplayName("the JVM options have a documented, overridable default (provisional until measured) and are not baked in")
	void jvmOptionsAreTunable() throws IOException {
		String value = String.valueOf(environment("controlweb-app").get("JAVA_TOOL_OPTIONS"));
		Matcher matcher = OPTIONAL_DEFAULT.matcher(value);

		assertThat(matcher.matches()).isTrue();
		assertThat(matcher.group(2)).contains("-Xmx").contains("-XX:+ExitOnOutOfMemoryError");
	}
}
