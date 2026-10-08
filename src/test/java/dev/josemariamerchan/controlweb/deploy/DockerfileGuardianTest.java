package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Static guardian of the Dockerfile and .dockerignore (no Docker needed): reproducible base images, unprivileged user,
 * a health check without curl, and a build context that cannot carry secrets, tests, .git or docs into the image.
 * What the BUILT image really contains is checked by deploy/verify-image.sh (and by the CI job).
 */
class DockerfileGuardianTest {

	private static final Path DOCKERFILE = Path.of("Dockerfile");
	private static final Path DOCKERIGNORE = Path.of(".dockerignore");

	/** The instructions of the Dockerfile with line continuations joined and comments removed. */
	private static List<String> instructions() throws IOException {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String raw : Files.readAllLines(DOCKERFILE)) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.endsWith("\\")) {
				current.append(line, 0, line.length() - 1).append(' ');
			}
			else {
				current.append(line);
				result.add(current.toString().strip());
				current.setLength(0);
			}
		}
		return result;
	}

	private static List<String> instructions(String keyword) throws IOException {
		return instructions().stream().filter(line -> line.toUpperCase().startsWith(keyword + " ")).toList();
	}

	@Test
	@DisplayName("every base image is pinned by an exact tag AND a sha256 digest (no latest, no floating major)")
	void baseImagesArePinned() throws IOException {
		List<String> froms = instructions("FROM");

		assertThat(froms).hasSize(2);
		Pattern pinned = Pattern.compile("FROM\\s+(\\S+):(\\S+)@sha256:[0-9a-f]{64}(\\s+AS\\s+\\S+)?", Pattern.CASE_INSENSITIVE);
		for (String from : froms) {
			Matcher matcher = pinned.matcher(from);
			assertThat(matcher.matches()).as("%s must be image:tag@sha256:digest", from).isTrue();
			String tag = matcher.group(2);
			assertThat(tag).doesNotContain("latest");
			assertThat(tag).as("exact version in %s", from).matches(".*\\d+\\.\\d+\\.\\d+.*");
		}
	}

	@Test
	@DisplayName("Java 21 JDK to build, Java 21 JRE (not the JDK) to run")
	void jdkToBuildJreToRun() throws IOException {
		List<String> froms = instructions("FROM");

		assertThat(froms.get(0)).contains("eclipse-temurin:21").contains("-jdk-");
		assertThat(froms.get(1)).contains("eclipse-temurin:21").contains("-jre-");
	}

	@Test
	@DisplayName("the runtime user is numeric and not root, and is set before the entry point")
	void runsAsAnUnprivilegedUser() throws IOException {
		List<String> all = instructions();
		List<String> users = instructions("USER");

		assertThat(users).isNotEmpty();
		String last = users.get(users.size() - 1).substring("USER ".length()).strip();
		assertThat(last).matches("\\d+(:\\d+)?");
		assertThat(Integer.parseInt(last.split(":")[0])).isGreaterThan(0);
		assertThat(all.indexOf(users.get(users.size() - 1))).isLessThan(all.indexOf(instructions("ENTRYPOINT").get(0)));
	}

	@Test
	@DisplayName("the runtime stage copies only from the build stage, never straight from the build context")
	void runtimeCopiesOnlyFromTheBuildStage() throws IOException {
		List<String> all = instructions();
		int runtimeStart = -1;
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).toUpperCase().startsWith("FROM") && all.get(i).toUpperCase().contains(" AS RUNTIME")) {
				runtimeStart = i;
			}
		}
		assertThat(runtimeStart).isPositive();
		for (String instruction : all.subList(runtimeStart, all.size())) {
			if (instruction.toUpperCase().startsWith("COPY ") || instruction.toUpperCase().startsWith("ADD ")) {
				assertThat(instruction).contains("--from=build");
			}
		}
	}

	@Test
	@DisplayName("no COPY of the whole context, no ADD, no remote downloads with curl|sh")
	void noBroadCopies() throws IOException {
		for (String instruction : instructions()) {
			String upper = instruction.toUpperCase();
			assertThat(upper.startsWith("ADD ")).as(instruction).isFalse();
			assertThat(upper).doesNotContainPattern("^COPY\\s+(--\\S+\\s+)*\\.\\s");
			assertThat(instruction).doesNotContainPattern("(curl|wget)[^|]*\\|\\s*(sh|bash)");
		}
	}

	@Test
	@DisplayName("a health check exists, uses wget (the image has no curl) and fails on an error status")
	void healthCheckWithoutCurl() throws IOException {
		List<String> health = instructions("HEALTHCHECK");

		assertThat(health).hasSize(1);
		assertThat(health.get(0)).contains("wget").contains("/actuator/health").contains("exit 1").doesNotContain("curl");
		assertThat(health.get(0)).contains("--start-period");
	}

	@Test
	@DisplayName("the health check leaves wide margin to start (>= 90 s, measured 24 s) and polls quickly while starting")
	void healthCheckTiming() throws IOException {
		String health = instructions("HEALTHCHECK").get(0);
		Matcher period = Pattern.compile("--start-period=(\\d+)s").matcher(health);
		Matcher startInterval = Pattern.compile("--start-interval=(\\d+)s").matcher(health);

		assertThat(period.find()).as("--start-period in seconds").isTrue();
		assertThat(Integer.parseInt(period.group(1))).isGreaterThanOrEqualTo(90);
		assertThat(startInterval.find()).as("--start-interval in seconds").isTrue();
		assertThat(Integer.parseInt(startInterval.group(1))).isBetween(1, 10);
		assertThat(health).contains("--interval=30s").contains("--retries=3");
	}

	@Test
	@DisplayName("the production profile is the default of the image, and no secret is baked into ENV or ARG")
	void noSecretsInEnvOrArg() throws IOException {
		String env = String.join("\n", instructions("ENV"));
		assertThat(env).contains("SPRING_PROFILES_ACTIVE=prod");
		for (String instruction : instructions()) {
			String upper = instruction.toUpperCase();
			if (upper.startsWith("ENV ") || upper.startsWith("ARG ")) {
				assertThat(upper).doesNotContainPattern("(PASSWORD|SECRET|TOKEN|API_?KEY|CREDENTIAL)");
			}
		}
	}

	@Test
	@DisplayName("the build skips the tests (they are not even in the context) and caps the Maven JVM")
	void buildSkipsTestsAndCapsMemory() throws IOException {
		String build = String.join("\n", instructions("RUN"));

		assertThat(build).contains("-Dmaven.test.skip=true").contains("-Xmx");
		assertThat(build).contains("--mount=type=cache");
	}

	@Test
	@DisplayName(".dockerignore is a whitelist that keeps .env, .git, docs, tests and target out of the build context")
	void dockerignoreIsAWhitelist() throws IOException {
		List<String> rules = Files.readAllLines(DOCKERIGNORE).stream().map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();

		assertThat(rules.get(0)).isEqualTo("**");
		List<String> allowed = rules.stream().filter(rule -> rule.startsWith("!")).map(rule -> rule.substring(1)).toList();
		assertThat(allowed).containsExactlyInAnyOrder("pom.xml", "mvnw", ".mvn/**", "src/main/**");
		for (String rule : allowed) {
			assertThat(rule).doesNotContain(".env").doesNotContain(".git").doesNotContain("docs").doesNotContain("src/test");
		}
	}
}
