package dev.josemariamerchan.controlweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
	@DisplayName("Dependabot updates Maven and GitHub Actions weekly")
	void dependabot() throws IOException {
		String config = read(DEPENDABOT);

		assertThat(config).contains("package-ecosystem: maven").contains("package-ecosystem: github-actions");
		assertThat(config.split("interval: weekly", -1)).hasSize(3);
	}
}
