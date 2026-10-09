package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/release.ps1 runs on the developer's Windows PC, with Windows PowerShell 5.1. These tests check it without building
 * or uploading anything: it parses, a dry run lists every step and touches nothing, and a few rules that took real
 * pain to learn (no binary data through a pipe, no unattended password prompt, never skip the CI confirmation).
 */
class ReleaseScriptTest {

	private static final Path SCRIPT = Path.of("deploy/release.ps1");

	@TempDir
	Path temp;

	private record Result(int exit, String output) {
	}

	private static boolean windowsPowerShell() {
		return System.getProperty("os.name", "").toLowerCase().contains("windows") && Files.exists(Path.of(
				System.getenv("SystemRoot") == null ? "C:/Windows" : System.getenv("SystemRoot"),
				"System32/WindowsPowerShell/v1.0/powershell.exe"));
	}

	private Result powershell(String... arguments) throws Exception {
		List<String> command = new java.util.ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass"));
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("PowerShell finished").isTrue();
		return new Result(process.exitValue(), output);
	}

	private String text() throws IOException {
		return Files.readString(SCRIPT, StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("release.ps1 has no syntax errors for Windows PowerShell")
	void parses() throws Exception {
		assumeTrue(windowsPowerShell(), "Windows PowerShell is not available");
		Path parser = temp.resolve("parse.ps1");
		Files.writeString(parser, """
				param($Path)
				$errors = $null
				[void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$null, [ref]$errors)
				if ($errors.Count -gt 0) { $errors | ForEach-Object { $_.Message }; exit 1 }
				Write-Output 'parsed'
				""");

		Result result = powershell("-File", parser.toString(), SCRIPT.toAbsolutePath().toString());

		assertThat(result.output()).contains("parsed");
		assertThat(result.exit()).as(result.output()).isZero();
	}

	@Test
	@DisplayName("a dry run lists every step (build, verify, save, upload, deploy) and creates nothing")
	void dryRunListsTheSteps() throws Exception {
		assumeTrue(windowsPowerShell(), "Windows PowerShell is not available");

		Result result = powershell("-File", SCRIPT.toAbsolutePath().toString(), "-SshTarget", "jose@example.invalid", "-DryRun");

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(result.output()).contains("[dry-run] docker build").contains("[dry-run] docker save -o")
				.contains("[dry-run] scp").contains("deploy/deploy.sh").contains("verify-image.sh").contains("worktree add");
		assertThat(result.output()).doesNotContain("ERROR");
		assertThat(Files.list(Path.of(System.getenv("TEMP"))).filter(path -> path.getFileName().toString().startsWith("controlweb-release-")))
				.as("a dry run leaves no working folder").isEmpty();
	}

	@Test
	@DisplayName("without -SshTarget it stops with a clear message and does nothing")
	void needsATarget() throws Exception {
		assumeTrue(windowsPowerShell(), "Windows PowerShell is not available");

		Result result = powershell("-Command", "$env:CONTROLWEB_SSH=$null; & '" + SCRIPT.toAbsolutePath() + "'");

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("-SshTarget");
	}

	@Test
	@DisplayName("binary data never travels through a pipe: the image is saved to a file with 'docker save -o'")
	void noPipesForBinaryData() throws IOException {
		String script = text();

		assertThat(script).contains("'save', '-o'");
		assertThat(script).doesNotContainPattern("(?i)docker[^\\n]*save[^\\n]*\\|");
		assertThat(script).doesNotContainPattern("\\|\\s*(ssh|scp|docker)\\b");
		assertThat(script).doesNotContain("docker load"); // loading is the server's job, from the uploaded file
	}

	@Test
	@DisplayName("the upload is verified: SHA-256 computed locally, written next to the image, checked by deploy.sh")
	void uploadIsChecksummed() throws IOException {
		String script = text();

		assertThat(script).contains("Get-FileHash").contains("SHA256").contains(".sha256").contains("UTF8Encoding($false)");
	}

	@Test
	@DisplayName("it never asks for a password, never uses a secret, and never skips the CI confirmation or the clean-tree check")
	void safetyRules() throws IOException {
		String script = text();

		assertThat(script).contains("BatchMode=yes");
		assertThat(script).doesNotContain("Invoke-Expression").doesNotContainIgnoringCase("password").doesNotContain("-Force:$true");
		assertThat(script).contains("-CiChecked").contains("status', '--porcelain'").contains("origin/main").contains("worktree', 'add'");
	}

	@Test
	@DisplayName("it builds from a throw-away worktree of the pushed commit, and cleans it up")
	void buildsFromACleanCopy() throws IOException {
		String script = text();

		assertThat(script).contains("'worktree', 'add', '--detach'").contains("worktree remove --force").contains("finally");
	}
}
