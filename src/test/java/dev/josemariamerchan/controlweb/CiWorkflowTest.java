package dev.josemariamerchan.controlweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Guards the CI definition: read-only, no secrets, every action pinned to a commit, Dependabot configured. */
class CiWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/ci.yml");
	private static final Path DEPENDABOT = Path.of(".github/dependabot.yml");

	private static String read(Path path) throws IOException {
		return Files.readString(path);
	}

	@Test
	@DisplayName("the workflow runs verify with Java 21 Temurin on push and pull request to main")
	void runsVerify() throws IOException {
		String workflow = read(WORKFLOW);

		assertThat(workflow).contains("./mvnw").contains("verify");
		assertThat(workflow).contains("distribution: temurin").contains("java-version: '21'").contains("cache: maven");
		assertThat(workflow).containsPattern("(?s)push:\\s+branches: \\[main\\]");
		assertThat(workflow).containsPattern("(?s)pull_request:\\s+branches: \\[main\\]");
	}

	@Test
	@DisplayName("permissions are read-only and no secret or private variable is referenced")
	void readOnlyAndNoSecrets() throws IOException {
		String workflow = read(WORKFLOW);

		assertThat(workflow).containsPattern("(?m)^permissions:\\s*\\R\\s+contents: read\\s*$");
		assertThat(workflow).doesNotContain("secrets.").doesNotContain("vars.").doesNotContain("pull_request_target");
		assertThat(workflow).doesNotContainPattern("(?m)^\\s+(contents|packages|id-token|pull-requests): write");
	}

	@Test
	@DisplayName("every third-party action is pinned to a full 40-character commit SHA")
	void actionsArePinned() throws IOException {
		Matcher uses = Pattern.compile("(?m)^\\s*(?:- )?uses:\\s*(\\S+)").matcher(read(WORKFLOW));
		int count = 0;
		while (uses.find()) {
			count++;
			assertThat(uses.group(1)).as("pinned action").matches("[\\w.-]+/[\\w.-]+@[0-9a-f]{40}");
		}
		assertThat(count).isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("Dependabot updates Maven, Docker (Dockerfile and compose) and GitHub Actions weekly")
	void dependabot() throws IOException {
		String config = read(DEPENDABOT);

		assertThat(config).contains("package-ecosystem: maven").contains("package-ecosystem: github-actions")
				.contains("package-ecosystem: docker\n").contains("package-ecosystem: docker-compose");
		assertThat(config.split("interval: weekly", -1)).hasSize(5);
	}

	@Test
	@DisplayName("Dependabot ignores MAJOR versions of postgres (data format) and eclipse-temurin (Java 21 LTS), and nothing else")
	@SuppressWarnings("unchecked")
	void dependabotIgnoresDatabaseAndJavaMajors() throws IOException {
		Map<String, Object> config = new Yaml().load(read(DEPENDABOT));
		Map<String, List<Map<String, Object>>> ignoresByEcosystem = new HashMap<>();
		for (Map<String, Object> update : (List<Map<String, Object>>) config.get("updates")) {
			ignoresByEcosystem.put((String) update.get("package-ecosystem"),
					(List<Map<String, Object>>) update.getOrDefault("ignore", List.of()));
		}

		assertThat(ignoresByEcosystem.get("docker-compose")).hasSize(1);
		assertThat(ignoresByEcosystem.get("docker-compose").get(0)).containsEntry("dependency-name", "postgres")
				.containsEntry("update-types", List.of("version-update:semver-major"));
		assertThat(ignoresByEcosystem.get("docker")).hasSize(1);
		assertThat(ignoresByEcosystem.get("docker").get(0)).containsEntry("dependency-name", "eclipse-temurin")
				.containsEntry("update-types", List.of("version-update:semver-major"));
		assertThat(ignoresByEcosystem.get("maven")).isEmpty();
		assertThat(ignoresByEcosystem.get("github-actions")).isEmpty();
	}

	@Test
	@DisplayName("the image job builds the production image WITHOUT publishing it and without secrets, and starts the real stack")
	void imageJobBuildsWithoutPublishing() throws IOException {
		String workflow = read(WORKFLOW).replace("\r\n", "\n");
		String job = workflow.substring(workflow.indexOf("\n  image:"));

		assertThat(job).contains("docker build").contains("deploy/verify-image.sh").contains("deploy/verify-container.sh")
				.contains("compose.prod.yml").contains("up -d --wait");
		assertThat(job).doesNotContain("docker push").doesNotContain("docker login").doesNotContain("--push")
				.doesNotContain("ghcr.io").doesNotContain("secrets.").doesNotContain("packages: write")
				.doesNotContain("build-push-action");
		assertThat(job).as("credentials are generated inside the job").contains("openssl rand");
		assertThat(job).contains("persist-credentials: false").contains("if: always()");
	}
}
