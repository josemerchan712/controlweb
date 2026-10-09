package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/backup/download-backups.ps1 runs on the owner's Windows PC with Windows PowerShell 5.1. These tests drive the REAL script with
 * a stand-in for ssh.exe (src/test/resources/fakedocker/reader-ssh.sh runs the real serve-backup.sh on a temporary folder), so the whole
 * path is exercised: request file -> cmd.exe redirection -> answer file -> size, SHA-256 and age-header checks -> rename.
 * -VerifyRestore runs against a fake age and a fake docker. Windows only (the script is for Windows).
 */
@Tag("slow-scripts")
class DownloadBackupsScriptTest {

	private static final Path SCRIPT = Path.of("deploy/backup/download-backups.ps1");
	private static final String A = "controlweb-20260101T041500Z.dump.age";
	private static final String B = "controlweb-20260102T041500Z.dump.age";
	private static final String C = "controlweb-20260103T041500Z.dump.age";
	private static final String BASH = "C:/Program Files/Git/bin/bash.exe";

	@TempDir
	Path temp;

	private Path server;
	private Path pc;
	private Path key;
	private Path standIn;
	private Path dockerLog;

	private record Result(int exit, String output) {
	}

	private static boolean windowsPowerShell() {
		return System.getProperty("os.name", "").toLowerCase().contains("windows") && Files.exists(Path.of(
				System.getenv("SystemRoot") == null ? "C:/Windows" : System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe"))
				&& Files.exists(Path.of(BASH));
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	/** A "copy": the age header followed by every byte value several times (so CR, LF and Ctrl-Z travel too). */
	private static byte[] payload(int seed) {
		byte[] header = "age-encryption.org/v1".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[header.length + 256 * 30];
		System.arraycopy(header, 0, bytes, 0, header.length);
		for (int i = header.length; i < bytes.length; i++) {
			bytes[i] = (byte) (i + seed);
		}
		return bytes;
	}

	@BeforeEach
	void world() throws Exception {
		assumeTrue(windowsPowerShell(), "Windows PowerShell and Git Bash are needed");
		server = Files.createDirectories(temp.resolve("server"));
		pc = temp.resolve("pc");
		key = temp.resolve("key");
		Files.writeString(key, "not a real key");
		Files.write(server.resolve(A), payload(1));
		Files.write(server.resolve(B), payload(2));
		standIn = temp.resolve("ssh.cmd");
		Files.writeString(standIn, "@\"" + BASH + "\" \"" + slashes(Path.of("src/test/resources/fakedocker/reader-ssh.sh")) + "\"\r\n");
		dockerLog = temp.resolve("docker.log");
	}

	private Result powershell(Map<String, String> environment, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
				SCRIPT.toAbsolutePath().toString()));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().put("CONTROLWEB_SSH_EXE", standIn.toString());
		builder.environment().put("SERVE_SCRIPT", slashes(Path.of("deploy/backup/serve-backup.sh")));
		builder.environment().put("BACKUP_DIR", slashes(server));
		builder.environment().put("FAKE_DOCKER_LOG", slashes(dockerLog));
		builder.environment().putAll(environment);
		Process process = builder.start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("PowerShell finished").isTrue();
		return new Result(process.exitValue(), output);
	}

	private Result download(Map<String, String> environment, String... extra) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("-SshTarget", "cwbackup@example.invalid", "-KeyFile", key.toString(), "-Destination", pc.toString(),
				"-MaxAgeHours", "1000000"));
		arguments.addAll(List.of(extra));
		return powershell(environment, arguments.toArray(String[]::new));
	}

	private List<String> localNames() throws IOException {
		if (!Files.exists(pc)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(pc)) {
			return stream.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private String script() throws IOException {
		return Files.readString(SCRIPT, StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("it has no syntax errors for Windows PowerShell 5.1 and is plain ASCII (5.1 reads other files as ANSI)")
	void parses() throws Exception {
		Path parser = temp.resolve("parse.ps1");
		Files.writeString(parser, "param($Path)\n$errors = $null\n[void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$null, [ref]$errors)\n"
				+ "if ($errors.Count -gt 0) { $errors | ForEach-Object { $_.Message }; exit 1 }\nWrite-Output 'parsed'\n");
		Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", parser.toString(),
				SCRIPT.toAbsolutePath().toString()).redirectErrorStream(true).start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		process.waitFor(60, TimeUnit.SECONDS);

		assertThat(output).contains("parsed");
		assertThat(script().chars().allMatch(c -> c < 128)).as("only ASCII characters").isTrue();
	}

	@Test
	@DisplayName("it downloads every copy byte for byte (binary data with CR, LF, Ctrl-Z) and a second run downloads nothing")
	void downloadsByteExact() throws Exception {
		Result first = download(Map.of());
		Result second = download(Map.of());

		assertThat(first.exit()).as(first.output()).isZero();
		assertThat(Files.readAllBytes(pc.resolve(A))).isEqualTo(payload(1));
		assertThat(Files.readAllBytes(pc.resolve(B))).isEqualTo(payload(2));
		assertThat(first.output()).contains("downloading " + A).contains("2 new");
		assertThat(second.exit()).isZero();
		assertThat(second.output()).doesNotContain("downloading").contains("0 new");
		assertThat(Files.readString(pc.resolve("last-run.txt"))).contains("OK downloaded 0");
		assertThat(pc.resolve("last-failure.txt")).doesNotExist();
		assertThat(localNames()).doesNotContain(".lock.partial").noneMatch(name -> name.endsWith(".partial"));
	}

	@Test
	@DisplayName("a new copy on the server is picked up on the next run; the old ones are not downloaded again")
	void picksUpNewCopies() throws Exception {
		download(Map.of());
		Files.write(server.resolve(C), payload(3));

		Result result = download(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(result.output()).contains("downloading " + C).doesNotContain("downloading " + A).contains("1 new");
		assertThat(Files.readAllBytes(pc.resolve(C))).isEqualTo(payload(3));
	}

	@Test
	@DisplayName("a corrupted download (one byte changed) is rejected by the checksum, leaves no file, keeps the good copies and exits 1")
	void corruptedDownloadIsRejected() throws Exception {
		download(Map.of(), "-Keep", "30");
		Files.delete(pc.resolve(B));
		Files.write(server.resolve(C), payload(3));

		Result result = download(Map.of("FAKE_SSH_MODE", "corrupt"));

		assertThat(result.exit()).as(result.output()).isEqualTo(1);
		assertThat(result.output()).contains("checksum mismatch");
		assertThat(localNames()).doesNotContain(B, C).contains(A).noneMatch(name -> name.endsWith(".partial"));
		assertThat(Files.readString(pc.resolve("last-failure.txt"))).contains("FAILED").contains("checksum mismatch");
	}

	@Test
	@DisplayName("a truncated download is rejected by the size check; a wrong checksum answer is rejected too; nothing is kept")
	void truncatedAndWrongChecksum() throws Exception {
		Result truncated = download(Map.of("FAKE_SSH_MODE", "truncate"));
		Result badSha = download(Map.of("FAKE_SSH_MODE", "badsha"));

		assertThat(truncated.exit()).isEqualTo(1);
		assertThat(truncated.output()).contains("size mismatch");
		assertThat(badSha.exit()).isEqualTo(1);
		assertThat(badSha.output()).contains("checksum mismatch");
		assertThat(localNames()).noneMatch(name -> name.startsWith("controlweb-") || name.endsWith(".partial"));
	}

	@Test
	@DisplayName("a file that is not an age file (no header) is rejected even if size and checksum match")
	void notAnAgeFile() throws Exception {
		Files.write(server.resolve(A), "just some text, long enough but not encrypted at all".getBytes(StandardCharsets.US_ASCII));
		Files.delete(server.resolve(B));

		Result result = download(Map.of());

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("age header");
		assertThat(localNames()).noneMatch(name -> name.startsWith("controlweb-"));
	}

	@Test
	@DisplayName("a connection failure exits 1, says why, writes last-failure.txt and deletes nothing")
	void connectionFailure() throws Exception {
		download(Map.of());

		Result result = download(Map.of("FAKE_SSH_MODE", "fail"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("FAILED").contains("Connection timed out");
		assertThat(localNames()).contains(A, B);
		assertThat(Files.readString(pc.resolve("last-failure.txt"))).contains("Connection timed out");
	}

	@Test
	@DisplayName("local rotation keeps the newest -Keep copies, and only after a fully successful run")
	void rotation() throws Exception {
		Files.write(server.resolve(C), payload(3));

		Result result = download(Map.of(), "-Keep", "2");

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(localNames()).contains(B, C).doesNotContain(A);
		Files.write(server.resolve("controlweb-20260104T041500Z.dump.age"), payload(4));
		Result failing = download(Map.of("FAKE_SSH_MODE", "corrupt"), "-Keep", "1");
		assertThat(failing.exit()).isEqualTo(1);
		assertThat(localNames()).as("a failed run rotates nothing").contains(B, C);
	}

	@Test
	@DisplayName("a server whose newest copy is older than -MaxAgeHours ends with exit 3 (the backup stopped), after downloading what it has")
	void staleServerCopy() throws Exception {
		Result result = powershell(Map.of(), "-SshTarget", "cwbackup@example.invalid", "-KeyFile", key.toString(), "-Destination", pc.toString(), "-MaxAgeHours", "36");

		assertThat(result.exit()).as(result.output()).isEqualTo(3);
		assertThat(result.output()).contains("STALE");
		assertThat(Files.readString(pc.resolve("last-failure.txt"))).contains("STALE");
		assertThat(localNames()).contains(A, B);
	}

	@Test
	@DisplayName("-Status: no copies or an old copy -> exit 1; a fresh copy -> exit 0; it also tells whether the scheduled task exists")
	void status() throws Exception {
		Result empty = powershell(Map.of(), "-Status", "-Destination", pc.toString());
		assertThat(empty.exit()).isEqualTo(1);
		assertThat(empty.output()).contains("No local copies");

		Files.createDirectories(pc);
		String fresh = "controlweb-" + java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusHours(2).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")) + ".dump.age";
		Files.write(pc.resolve(fresh), payload(5));
		Result ok = powershell(Map.of(), "-Status", "-Destination", pc.toString());
		assertThat(ok.exit()).as(ok.output()).isZero();
		assertThat(ok.output()).contains(fresh).contains("ControlWeb backup download");

		Result old = powershell(Map.of(), "-Status", "-Destination", pc.toString(), "-MaxAgeHours", "1");
		assertThat(old.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("-DryRun downloads and registers nothing and says what it would do; -List only lists")
	void dryRunAndList() throws Exception {
		Result dry = download(Map.of(), "-DryRun");
		Result task = powershell(Map.of(), "-InstallTask", "-DryRun", "-SshTarget", "cwbackup@example.invalid", "-TaskTime", "10:30");
		Result list = download(Map.of(), "-List");

		assertThat(dry.exit()).isZero();
		assertThat(dry.output()).contains("[dry-run]");
		assertThat(pc).doesNotExist();
		assertThat(task.exit()).as(task.output()).isZero();
		assertThat(task.output()).contains("daily at 10:30").contains("StartWhenAvailable").contains("RunOnlyIfNetworkAvailable").contains("retries");
		assertThat(list.exit()).isZero();
		assertThat(list.output()).contains(A).contains(B);
		assertThat(pc).doesNotExist();
		assertThat(powershell(Map.of(), "-InstallTask", "-DryRun", "-SshTarget", "x@y", "-TaskTime", "25:00").exit()).as("bad time").isNotZero();
	}

	@Test
	@DisplayName("static rules that took pain to learn: no PowerShell pipe or '>' for ssh, redirection by cmd.exe, no key or secret in the script")
	void staticRules() throws IOException {
		String text = script();

		assertThat(text).contains("/s /c").contains("< \"{3}\" > \"{4}\"").contains("StartWhenAvailable").contains("RunOnlyIfNetworkAvailable").contains("-RestartCount 3");
		assertThat(text).doesNotContainPattern("(?m)^\\s*&\\s*\\$?ssh.*[|>]").doesNotContain("| ssh").doesNotContain("ssh |").doesNotContain("Out-File").doesNotContain("Set-Content")
				.doesNotContain("-Encoding Byte").doesNotContain("Invoke-Expression").doesNotContain("iex ").doesNotContain("StrictHostKeyChecking=no")
				.doesNotContain("AGE-SECRET-KEY-1").doesNotContain("PRIVATE KEY");
		assertThat(text).contains("-o BatchMode=yes").contains("StrictHostKeyChecking=yes").contains("IdentitiesOnly=yes");
	}

	// ---- -VerifyRestore with a fake age and a fake docker ----------------------------------------------------------------------

	private Path fakeTools(boolean withAge) throws IOException {
		Path bin = Files.createDirectories(temp.resolve("tools"));
		Files.writeString(bin.resolve("docker.cmd"), "@\"" + BASH + "\" \"" + slashes(Path.of("src/test/resources/fakedocker/verify-docker.sh")) + "\" %*\r\n");
		if (withAge) {
			// age --decrypt -i ID -o OUT IN : "decrypts" by copying the file, unless FAKE_AGE_FAIL=1; the identity must be passed with -i
			Files.writeString(bin.resolve("age.cmd"), "@if \"%FAKE_AGE_FAIL%\"==\"1\" exit /b 1\r\n@if not \"%~2\"==\"-i\" exit /b 3\r\n@copy /y \"%~6\" \"%~5\" >nul\r\n");
		}
		return bin;
	}

	private Path storedPlainCopy(String header) throws IOException {
		Files.createDirectories(pc);
		Path copy = pc.resolve(B);
		Files.writeString(copy, header + " the rest of a pretend database archive");
		return copy;
	}

	private Path identity() throws IOException {
		Path identity = temp.resolve("identity.txt");
		Files.writeString(identity, "# created: test\n# public key: age1xxxx\nAGE-SECRET-KEY-1TESTTESTTESTTESTTESTTESTTESTTESTTESTTESTTESTTESTTEST\n");
		return identity;
	}

	private Result verify(Path tools, Map<String, String> environment, String... extra) throws Exception {
		Map<String, String> env = new java.util.HashMap<>(environment);
		env.put("CONTROLWEB_DOCKER_EXE", tools.resolve("docker.cmd").toString());
		if (Files.exists(tools.resolve("age.cmd"))) {
			env.put("CONTROLWEB_AGE_EXE", tools.resolve("age.cmd").toString());
		} else {
			env.put("CONTROLWEB_AGE_EXE", tools.resolve("no-age-installed.cmd").toString());
		}
		List<String> arguments = new ArrayList<>(List.of("-VerifyRestore", "-Destination", pc.toString(), "-Identity", identity().toString()));
		arguments.addAll(List.of(extra));
		return powershell(env, arguments.toArray(String[]::new));
	}

	private long leftoverTempFolders() throws IOException {
		try (Stream<Path> stream = Files.list(Path.of(System.getenv("TEMP")))) {
			return stream.filter(path -> path.getFileName().toString().startsWith("controlweb-verify-")).count();
		}
	}

	@Test
	@DisplayName("-VerifyRestore: decrypts into a temp folder, restores in a throw-away container with the compose image, checks 6 tables, then deletes everything")
	void verifyRestoreWorks() throws Exception {
		storedPlainCopy("PGDMP");
		long before = leftoverTempFolders();

		Result result = verify(fakeTools(true), Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(result.output()).contains("RESTORE OK").contains("sites").contains("flyway_schema_history").contains("3 3").doesNotContain("AGE-SECRET-KEY");
		String docker = Files.readString(dockerLog);
		String image = Files.readAllLines(Path.of("compose.prod.yml")).stream().map(String::strip).filter(line -> line.startsWith("image: postgres:")).findFirst().orElseThrow()
				.substring("image: ".length());
		assertThat(docker).contains("run -d --name controlweb-restore-check-").contains("-m 512m").contains(image).contains("pg_restore -U postgres -d restorecheck --no-owner --no-privileges --exit-on-error --single-transaction")
				.contains("rm -f controlweb-restore-check-");
		assertThat(leftoverTempFolders()).as("the decrypted file's folder is deleted").isEqualTo(before);
	}

	@Test
	@DisplayName("-VerifyRestore: a failed restore, a missing table, a wrong identity, a non-archive and Docker down all fail (exit 1), and the container and decrypted file are still removed")
	void verifyRestoreFailures() throws Exception {
		storedPlainCopy("PGDMP");
		Path tools = fakeTools(true);
		long before = leftoverTempFolders();

		Result restoreFails = verify(tools, Map.of("FAKE_RESTORE_EXIT", "1"));
		assertThat(restoreFails.exit()).isEqualTo(1);
		assertThat(restoreFails.output()).contains("RESTORE FAILED").doesNotContain("RESTORE OK");
		assertThat(Files.readString(dockerLog)).contains("rm -f controlweb-restore-check-");

		Files.delete(dockerLog);
		Result missing = verify(tools, Map.of("FAKE_MISSING_TABLE", "incidents"));
		assertThat(missing.exit()).isEqualTo(1);
		assertThat(missing.output()).contains("tables missing").contains("incidents");

		Result wrongKey = verify(tools, Map.of("FAKE_AGE_FAIL", "1"));
		assertThat(wrongKey.exit()).isEqualTo(1);
		assertThat(wrongKey.output()).contains("could not decrypt");

		storedPlainCopy("NOTPG");
		Result notArchive = verify(tools, Map.of());
		assertThat(notArchive.exit()).isEqualTo(1);
		assertThat(notArchive.output()).contains("not a PostgreSQL custom-format archive");

		storedPlainCopy("PGDMP");
		Result dockerDown = verify(tools, Map.of("FAKE_DOCKER_DOWN", "1"));
		assertThat(dockerDown.exit()).isEqualTo(1);
		assertThat(dockerDown.output()).contains("Docker is not running");
		assertThat(leftoverTempFolders()).isEqualTo(before);
	}

	@Test
	@DisplayName("-VerifyRestore: asks for the identity, checks it is an age identity, and explains how to install age when it is missing")
	void verifyRestoreRequirements() throws Exception {
		storedPlainCopy("PGDMP");
		Path tools = fakeTools(false);

		Result noAge = verify(tools, Map.of());   // verify() points CONTROLWEB_AGE_EXE at a file that does not exist when there is no fake age
		assertThat(noAge.exit()).isNotZero();
		assertThat(noAge.output()).contains("winget install FiloSottile.age");

		Result noIdentity = powershell(Map.of("CONTROLWEB_AGE_EXE", tools.resolve("no-age-installed.cmd").toString(), "CONTROLWEB_DOCKER_EXE", tools.resolve("docker.cmd").toString()), "-VerifyRestore", "-Destination", pc.toString());
		assertThat(noIdentity.exit()).isNotZero();
		assertThat(noIdentity.output()).contains("-Identity");

		Path notIdentity = temp.resolve("not-identity.txt");
		Files.writeString(notIdentity, "hello");
		Result wrong = powershell(Map.of("CONTROLWEB_AGE_EXE", tools.resolve("no-age-installed.cmd").toString(), "CONTROLWEB_DOCKER_EXE", tools.resolve("docker.cmd").toString()), "-VerifyRestore", "-Destination", pc.toString(), "-Identity", notIdentity.toString());
		assertThat(wrong.exit()).isNotZero();
		assertThat(wrong.output()).contains("is not an age identity file");
	}

	@Test
	@DisplayName("-VerifyRestore -DryRun lists the steps and needs neither age, Docker nor the identity")
	void verifyRestoreDryRun() throws Exception {
		storedPlainCopy("PGDMP");

		Result result = powershell(Map.of(), "-VerifyRestore", "-DryRun", "-Destination", pc.toString());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(result.output()).contains("age --decrypt").contains("docker run -d").contains("pg_restore").contains("delete <temp>");
	}
}
