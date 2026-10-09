package dev.josemariamerchan.controlweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ShellCheck runs in CI over every tracked *.sh file. These tests keep it honest: the job cannot be weakened (no excluded warnings, no
 * "|| true", no unpinned image, no global ignore file), and every warning that is silenced inside a script says WHY, in writing.
 */
class ShellcheckGuardianTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/ci.yml");
	private static final Pattern DISABLE = Pattern.compile("#\\s*shellcheck\\s+disable=([A-Za-z0-9,]+)(.*)$");

	private static String job() throws IOException {
		String workflow = Files.readString(WORKFLOW).replace("\r\n", "\n");
		int start = workflow.indexOf("\n  shellcheck:\n");
		assertThat(start).as("a job named shellcheck").isGreaterThan(0);
		int end = workflow.indexOf("\n  image:\n", start);
		String text = workflow.substring(start, end > 0 ? end : workflow.length());
		// the comments of the job explain what is NOT allowed; the rules below apply to what it actually runs
		return text.lines().filter(line -> !line.strip().startsWith("#")).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
	}

	@Test
	@DisplayName("CI has a shellcheck job over EVERY tracked .sh file, with the image pinned by digest, and nothing in it weakens the check")
	void theJobIsStrict() throws IOException {
		String job = job();

		assertThat(job).contains("git ls-files -z '*.sh' | xargs -0 docker run --rm").containsPattern("koalaman/shellcheck@sha256:[0-9a-f]{64}")
				.contains("--source-path=SCRIPTDIR").contains("--external-sources");
		assertThat(job).doesNotContain("|| true").doesNotContain("--exclude").doesNotContain(" -e SC").doesNotContain("--severity").doesNotContain(" -S ").doesNotContain("continue-on-error")
				.doesNotContain("--shell=").doesNotContain("SHELLCHECK_OPTS").doesNotContain("ignore").doesNotContain("grep -v").doesNotContain(":stable ").doesNotContain(":latest");
		assertThat(job).containsPattern("uses: actions/checkout@[0-9a-f]{40}");
	}

	@Test
	@DisplayName("there is no .shellcheckrc anywhere (it could silence warnings for the whole repository without a word in the scripts)")
	void noGlobalIgnoreFile() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("."))) {
			List<Path> found = files.filter(path -> path.getFileName() != null && path.getFileName().toString().equals(".shellcheckrc"))
					.filter(path -> !path.toString().contains("node_modules") && !path.toString().contains("target")).toList();
			assertThat(found).isEmpty();
		}
	}

	@Test
	@DisplayName("every 'shellcheck disable=' in a script names its codes and gives a reason of at least 15 characters after a second '#'")
	void everySilencedWarningHasAReason() throws IOException {
		List<String> problems = new ArrayList<>();
		int found = 0;
		List<Path> scripts;
		try (Stream<Path> files = Files.walk(Path.of("."))) {
			scripts = files.filter(path -> path.toString().endsWith(".sh") && !path.toString().contains("target") && !path.toString().contains(".git" + java.io.File.separator)).toList();
		}
		for (Path script : scripts) {
			int number = 0;
			for (String line : Files.readAllLines(script)) {
				number++;
				if (line.contains("shellcheck disable") || line.contains("shellcheck  disable")) {
					found++;
					java.util.regex.Matcher matcher = DISABLE.matcher(line);
					if (!matcher.find()) {
						problems.add(script + ":" + number + " is not in the form '# shellcheck disable=SCxxxx # reason'");
						continue;
					}
					String reason = matcher.group(2).strip();
					if (!reason.startsWith("#") || reason.substring(1).strip().length() < 15) {
						problems.add(script + ":" + number + " has no written reason (needs '# shellcheck disable=" + matcher.group(1) + " # why')");
					}
					if (!matcher.group(1).matches("SC\\d{4}(,SC\\d{4})*")) {
						problems.add(script + ":" + number + " does not name specific SC codes");
					}
				}
			}
		}
		assertThat(problems).isEmpty();
		assertThat(found).as("the repository does silence a few warnings, each with a reason").isGreaterThan(5);
		assertThat(scripts.size()).as("the scripts are found").isGreaterThan(20);
	}

	@Test
	@DisplayName("shell scripts keep LF line endings in git (a CRLF would make bash fail on the server): .gitattributes says so")
	void scriptsKeepLf() throws IOException {
		String attributes = Files.exists(Path.of(".gitattributes")) ? Files.readString(Path.of(".gitattributes")) : "";

		assertThat(attributes).containsPattern("\\*\\.sh\\s+text\\s+eol=lf");
	}
}
