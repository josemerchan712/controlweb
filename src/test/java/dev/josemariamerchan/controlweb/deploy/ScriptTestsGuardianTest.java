package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules for the tests that drive shell scripts, learned when the CI on Linux failed 27 times while everything passed on Windows (the
 * stand-in scripts had no execute bit there, and one assertion depended on the iteration order of a HashSet). Windows hides this whole
 * family of mistakes, so the rules are checked statically, on every OS.
 */
class ScriptTestsGuardianTest {

	private static final Path TESTS = Path.of("src/test/java");

	private static List<Path> javaTests() throws IOException {
		try (Stream<Path> files = Files.walk(TESTS)) {
			return files.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}

	private static List<String> code(Path file) throws IOException {
		return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> {
			String stripped = line.strip();
			return !stripped.startsWith("*") && !stripped.startsWith("//") && !stripped.startsWith("/*");
		}).collect(Collectors.toList());
	}

	@Test
	@DisplayName("fakes are made executable ONLY by ScriptFixtures (which checks it): no test calls setExecutable or chmod itself")
	void onlyScriptFixturesMakesFilesExecutable() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : javaTests()) {
			if (file.getFileName().toString().equals("ScriptFixtures.java") || file.getFileName().toString().equals("ScriptTestsGuardianTest.java")) {
				continue;
			}
			for (String line : code(file)) {
				if (line.contains("setExecutable(") || line.contains("PosixFilePermissions.fromString(\"rwx") && line.contains("setPosixFilePermissions") && line.contains("fake")) {
					offenders.add(file.getFileName() + ": " + line.strip());
				}
			}
		}
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("a script under src/test/resources/fakedocker is never handed to the script under test straight from the source tree (git stores it 0644): it goes through ScriptFixtures")
	void fakesFromTheSourceTreeAreCopiedExecutable() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : javaTests()) {
			String name = file.getFileName().toString();
			if (name.equals("DownloadBackupsScriptTest.java") || name.equals("ScriptTestsGuardianTest.java")) {
				continue; // Windows-only: bash.exe is given the script as an ARGUMENT (no execute bit needed), through .cmd stand-ins
			}
			for (String line : code(file)) {
				if (line.contains("src/test/resources/fakedocker/") && !line.contains("ScriptFixtures.copyFile")) {
					offenders.add(name + ": " + line.strip());
				}
			}
		}
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("modes are compared as 'rwx------' strings (PosixFilePermissions.toString), never with the toString() of the Set, whose order depends on the JVM")
	void permissionSetsAreNotCompareddAsStrings() throws IOException {
		Pattern bad = Pattern.compile("getPosixFilePermissions\\([^;]*\\)\\s*\\.toString\\(\\)");
		List<String> offenders = new ArrayList<>();
		for (Path file : javaTests()) {
			if (file.getFileName().toString().equals("ScriptTestsGuardianTest.java")) {
				continue;
			}
			for (String line : code(file)) {
				if (bad.matcher(line).find()) {
					offenders.add(file.getFileName() + ": " + line.strip());
				}
			}
		}
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("every shell script, unit, fake and fragment in the repository has LF line endings in the working tree (a CR breaks the shebang on Linux)")
	void scriptsAreLf() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String root : List.of("deploy", "src/test/resources/fakedocker")) {
			try (Stream<Path> files = Files.walk(Path.of(root))) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					String name = file.getFileName().toString();
					if (name.endsWith(".ps1") || name.endsWith(".cmd")) {
						continue; // Windows files
					}
					if (new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).indexOf('\r') >= 0) {
						offenders.add(file.toString());
					}
				}
			}
		}
		assertThat(offenders).as("files with CR characters (check .gitattributes)").isEmpty();
		String attributes = Files.readString(Path.of(".gitattributes"));
		for (String pattern : List.of("*.sh", "*.service", "*.timer", "*.sudoers", "*.caddy", "deploy/hooks/*")) {
			assertThat(attributes).as(".gitattributes must pin %s to LF", pattern).containsPattern("(?m)^" + Pattern.quote(pattern) + "\\s+text\\s+eol=lf");
		}
	}

	@Test
	@DisplayName("ScriptFixtures itself: it makes a script executable, refuses CRLF/a missing shebang, and its copies are independent of the source file")
	void fixturesWork() throws IOException {
		Path dir = Files.createTempDirectory("fixtures");
		Path fake = ScriptFixtures.write(dir.resolve("fake"), "#!/bin/sh\r\nexit 0\r\n");

		assertThat(Files.isExecutable(fake)).isTrue();
		assertThat(Files.readString(fake)).isEqualTo("#!/bin/sh\nexit 0\n");
		Path notAScript = Files.writeString(dir.resolve("plain"), "hello\n");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> ScriptFixtures.executable(notAScript)).isInstanceOf(AssertionError.class).hasMessageContaining("shebang");
		Path copy = ScriptFixtures.copyFile(Path.of("src/test/resources/fakedocker/fake-iptables.sh"), dir, "iptables");
		assertThat(Files.isExecutable(copy)).isTrue();
		assertThat(copy).isNotEqualTo(Path.of("src/test/resources/fakedocker/fake-iptables.sh"));
	}

	@Test
	@DisplayName("the Linux runner (deploy/test-linux.ps1) builds the tree like a CI checkout, runs as a non-root user and demands the CI's must-run classes; the pre-commit hook calls it")
	void linuxRunnerIsWiredUp() throws IOException {
		String runner = Files.readString(Path.of("deploy/test-linux.ps1"));
		String inner = Files.readString(Path.of("deploy/linux-test/run-tests.sh"));
		String dockerfile = Files.readString(Path.of("deploy/linux-test/Dockerfile"));
		String hook = Files.readString(Path.of("deploy/hooks/pre-commit"));

		assertThat(runner).contains("core.autocrlf=false").contains("archive --format=tar").contains("--output=").contains("write-tree").contains("GIT_INDEX_FILE")
				.contains("for test in").contains("ci.yml").doesNotContain("2>&1");
		assertThat(inner).contains("skipped").contains("must_run").contains("./mvnw");
		assertThat(dockerfile).containsPattern("FROM eclipse-temurin:21-jdk@sha256:[0-9a-f]{64}").contains("USER runner").doesNotContain("USER root\nCMD");
		assertThat(hook).contains("deploy/test-linux.ps1 -Staged").contains("core.hooksPath").doesNotContain("--no-verify ");
		assertThat(Files.readString(Path.of("CLAUDE.md"))).contains("REGLA: los tests de scripts se prueban en Linux");
	}
}
