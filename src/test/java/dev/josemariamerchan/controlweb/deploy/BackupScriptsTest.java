package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the real backup.sh, restore-test.sh and restore.sh (real bash) against a fake docker (the PostgreSQL container) and a fake
 * age. The decisions under test: a copy is only a copy when EVERYTHING checked out, and a failed run changes nothing -- above all it
 * never rotates out the previous copies; the weekly restore test always removes its temporary database; a restore never starts
 * without --yes, without a working identity, or from an identity left on a disk.
 */
@Tag("slow-scripts")
class BackupScriptsTest {

	private static final String BASH = bashExecutable();
	private static final String RECIPIENT = "age1" + "q".repeat(58);
	private static final String HEADER = "age-encryption.org/v1";

	/** The "database dump": 2 MB, far beyond any pipe buffer, so a truncated stream is noticed. */
	private static byte[] dump;

	@TempDir
	Path temp;

	private Path backups;
	private Path stateDir;
	private Path fakeState;
	private Path bin;
	private Path recipientFile;
	private Path dumpFile;

	private record Result(int exit, String output) {
	}

	@BeforeAll
	static void prepare() {
		assumeTrue(BASH != null, "bash is not available");
		dump = new byte[2 * 1024 * 1024];
		new Random(42).nextBytes(dump);
	}

	private static String bashExecutable() {
		for (String candidate : List.of("C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash")) {
			if (Files.exists(Path.of(candidate))) {
				return candidate;
			}
		}
		return null;
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase().contains("windows");
	}

	@BeforeEach
	void world() throws Exception {
		backups = Files.createDirectories(temp.resolve("backups"));
		stateDir = temp.resolve("state");
		fakeState = Files.createDirectories(temp.resolve("fake-state"));
		bin = Files.createDirectories(temp.resolve("bin"));
		for (String[] stub : new String[][] { { "backup-docker", "docker" }, { "backup-age", "age" } }) {
			String script = new String(getClass().getResourceAsStream("/fakedocker/" + stub[0] + ".sh").readAllBytes(),
					StandardCharsets.UTF_8).replace("\r\n", "\n");
			Files.writeString(bin.resolve(stub[1]), script, StandardCharsets.UTF_8);
			bin.resolve(stub[1]).toFile().setExecutable(true);
		}
		recipientFile = temp.resolve("backup-recipient.txt");
		Files.writeString(recipientFile, "# public key\n" + RECIPIENT + "\n");
		dumpFile = temp.resolve("dump.bin");
		Files.write(dumpFile, dump);
	}

	private Map<String, String> environment(Map<String, String> extra) {
		Map<String, String> environment = new HashMap<>();
		environment.put("PATH", bin + java.io.File.pathSeparator + (System.getenv("PATH") == null ? "" : System.getenv("PATH")));
		environment.put("ALLOW_NON_ROOT", "1");
		environment.put("IDENTITY_MODE_CHECK", "no"); // chmod does nothing on Windows; one Linux-only test turns it back on
		environment.put("BACKUP_DIR", slashes(backups));
		environment.put("STATE_DIR", slashes(stateDir));
		environment.put("LOCK_DIR", slashes(temp.resolve("lock")));
		environment.put("RECIPIENT_FILE", slashes(recipientFile));
		environment.put("MIN_FREE_MB", "1");
		environment.put("WAIT_SECONDS", "10");
		environment.put("FAKE_STATE", slashes(fakeState));
		environment.put("FAKE_DUMP_FILE", slashes(dumpFile));
		environment.putAll(extra);
		return environment;
	}

	private Process start(Map<String, String> extra, String script, String... arguments) throws IOException {
		List<String> command = new ArrayList<>(List.of(BASH, "deploy/backup/" + script));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().putAll(environment(extra));
		return builder.start();
	}

	private Result finish(Process process) throws Exception {
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the script finished").isTrue();
		return new Result(process.exitValue(), output);
	}

	private Result run(Map<String, String> extra, String script, String... arguments) throws Exception {
		return finish(start(extra, script, arguments));
	}

	private Result backup(Map<String, String> extra) throws Exception {
		return run(extra, "backup.sh");
	}

	private List<Path> stored() throws IOException {
		try (Stream<Path> files = Files.list(backups)) {
			return files.filter(path -> path.getFileName().toString().startsWith("controlweb-")).sorted().toList();
		}
	}

	private List<String> everythingInBackupDir() throws IOException {
		try (Stream<Path> files = Files.list(backups)) {
			return files.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private Path oldCopy(String stamp, long daysOld) throws IOException {
		Path file = backups.resolve("controlweb-" + stamp + ".dump.age");
		Files.write(file, (HEADER + "\n" + "old contents ".repeat(40)).getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(daysOld))));
		return file;
	}

	private String calls() throws IOException {
		Path log = fakeState.resolve("calls.log");
		return Files.exists(log) ? Files.readString(log) : "";
	}

	private String state(String name) throws IOException {
		Path file = stateDir.resolve(name);
		return Files.exists(file) ? Files.readString(file).strip() : "";
	}

	// ======================================================================= backup.sh

	@Test
	@DisplayName("backup: an encrypted copy appears, byte for byte the whole dump (the stream is drained, never cut), nothing else is left")
	void backupSucceeds() throws Exception {
		Result result = backup(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		List<Path> copies = stored();
		assertThat(copies).hasSize(1);
		assertThat(copies.get(0).getFileName().toString()).matches("controlweb-\\d{8}T\\d{6}Z\\.dump\\.age");
		byte[] content = Files.readAllBytes(copies.get(0));
		assertThat(new String(content, 0, HEADER.length(), StandardCharsets.UTF_8)).isEqualTo(HEADER);
		byte[] body = java.util.Arrays.copyOfRange(content, HEADER.length() + 1, content.length);
		assertThat(body).as("the encrypted copy holds the complete dump, even though pg_restore --list stopped reading early")
				.isEqualTo(dump);
		assertThat(everythingInBackupDir()).as("no partial file, no work folder").hasSize(1);
		assertThat(state("last")).contains(copies.get(0).getFileName().toString()).contains("bytes");
		assertThat(stateDir.resolve("last-failure")).doesNotExist();
		assertThat(calls()).contains("age --recipient " + RECIPIENT).contains("pg_restore --list");
		assertThat(Files.exists(stateDir.resolve("last.tmp"))).isFalse();
	}

	@Test
	@DisplayName("backup: the copy is private (0600) and the folder is 0700")
	void backupPermissions() throws Exception {
		assumeTrue(!windows(), "file modes are not meaningful on Windows");

		Result result = backup(Map.of());

		assertThat(result.exit()).isZero();
		assertThat(Files.getPosixFilePermissions(stored().get(0)).toString()).isEqualTo("[OWNER_READ, OWNER_WRITE]");
		assertThat(Files.getPosixFilePermissions(backups).toString()).isEqualTo("[OWNER_READ, OWNER_WRITE, OWNER_EXECUTE]");
	}

	@Test
	@DisplayName("backup: rotation removes copies older than 14 days but the newest three always stay")
	void rotation() throws Exception {
		Path d1 = oldCopy("20260901T000000Z", 1);
		Path d5 = oldCopy("20260801T000000Z", 5);
		Path d16 = oldCopy("20260701T000000Z", 16);
		Path d20 = oldCopy("20260601T000000Z", 20);
		Path d40 = oldCopy("20260501T000000Z", 40);

		Result result = backup(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(d1).exists();
		assertThat(d5).exists();
		assertThat(d16).doesNotExist();
		assertThat(d20).doesNotExist();
		assertThat(d40).doesNotExist();
		assertThat(stored()).hasSize(3);
	}

	@Test
	@DisplayName("backup: even if every copy is old, the newest three are never rotated out")
	void rotationKeepsTheNewestThree() throws Exception {
		Path d20 = oldCopy("20260601T000000Z", 20);
		Path d30 = oldCopy("20260501T000000Z", 30);
		Path d40 = oldCopy("20260401T000000Z", 40);

		Result result = backup(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(d20).exists();
		assertThat(d30).exists();
		assertThat(d40).doesNotExist();
		assertThat(stored()).hasSize(3);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAKE_LIST_FAIL", "FAKE_TOC_MISSING", "FAKE_DUMP_FAIL", "FAKE_AGE_FAIL", "FAKE_AGE_EMPTY", "FAKE_AGE_GARBAGE",
			"FAKE_AGE_TINY", "FAKE_LIST_FAIL_AFTER_TOC" })
	@DisplayName("backup: a copy that pg_restore --list rejects, that misses a table, or that was not produced/encrypted is NOT accepted, and nothing older is rotated")
	void badCopyIsNotAcceptedAndNothingIsRotated(String problem) throws Exception {
		// copies that WOULD be rotated out by a successful run (all older than 14 days, beyond the newest three)
		List<Path> before = List.of(oldCopy("20260901T000000Z", 1), oldCopy("20260801T000000Z", 2), oldCopy("20260701T000000Z", 3),
				oldCopy("20260601T000000Z", 20), oldCopy("20260501T000000Z", 30), oldCopy("20260401T000000Z", 40));
		Map<String, String> fault = problem.equals("FAKE_TOC_MISSING") ? Map.of(problem, "checks") : Map.of(problem, "1");

		Result result = backup(fault);

		assertThat(result.exit()).as(result.output()).isEqualTo(1);
		assertThat(result.output()).contains("BACKUP FAILED");
		for (Path copy : before) {
			assertThat(copy).as("%s must be untouched after a failed backup (%s)", copy.getFileName(), problem).exists();
		}
		assertThat(everythingInBackupDir()).as("no new copy, no partial file, no work folder").hasSize(before.size());
		assertThat(state("last-failure")).isNotEmpty();
		assertThat(stateDir.resolve("last")).doesNotExist();
	}

	@Test
	@DisplayName("backup: after a failure the next good run still works and clears the failure note")
	void recoveryAfterFailure() throws Exception {
		assertThat(backup(Map.of("FAKE_LIST_FAIL", "1")).exit()).isEqualTo(1);
		assertThat(state("last-failure")).isNotEmpty();

		Result result = backup(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(stateDir.resolve("last-failure")).doesNotExist();
		assertThat(stored()).hasSize(1);
	}

	@Test
	@DisplayName("backup: no public key, a PRIVATE key on the server, or two keys: refused before the database is touched")
	void recipientProblemsStopEverything() throws Exception {
		Files.delete(recipientFile);
		Result missing = backup(Map.of());

		Files.writeString(recipientFile, RECIPIENT + "\nAGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ\n");
		Result privateKey = backup(Map.of());

		Files.writeString(recipientFile, RECIPIENT + "\n" + "age1" + "w".repeat(58) + "\n");
		Result two = backup(Map.of());

		assertThat(missing.output()).contains("no public key");
		assertThat(privateKey.output()).contains("PRIVATE key");
		assertThat(two.output()).contains("exactly one");
		assertThat(List.of(missing.exit(), privateKey.exit(), two.exit())).containsOnly(1);
		assertThat(calls()).doesNotContain("pg_dump");
		assertThat(stored()).isEmpty();
	}

	@Test
	@DisplayName("backup: without root it refuses (the scripts are for root, started by systemd)")
	void needsRoot() throws Exception {
		assumeTrue(!"root".equals(System.getProperty("user.name")), "running as root");
		Map<String, String> environment = new HashMap<>(environment(Map.of()));
		environment.remove("ALLOW_NON_ROOT");
		ProcessBuilder builder = new ProcessBuilder(BASH, "deploy/backup/backup.sh").redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().remove("ALLOW_NON_ROOT");
		builder.environment().putAll(environment);

		Result result = finish(builder.start());

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("must run as root");
		assertThat(calls()).doesNotContain("pg_dump");
	}

	@Test
	@DisplayName("backup: database container not running, or no free space: refused with a clear reason")
	void environmentProblems() throws Exception {
		Result down = backup(Map.of("FAKE_DB_DOWN", "1"));
		Result noSpace = backup(Map.of("MIN_FREE_MB", "999999999"));

		assertThat(down.output()).contains("database container is not running");
		assertThat(noSpace.output()).contains("MB free");
		assertThat(List.of(down.exit(), noSpace.exit())).containsOnly(1);
		assertThat(state("last-failure")).isNotEmpty();
		assertThat(stored()).isEmpty();
	}

	@Test
	@DisplayName("backup: two at the same time are impossible; a stale lock of a dead process does not block")
	void locking() throws Exception {
		Process first = start(Map.of("FAKE_DUMP_DELAY", "6"), "backup.sh");
		Thread.sleep(3500);

		Result second = backup(Map.of());
		Result firstResult = finish(first);

		assertThat(second.exit()).isEqualTo(1);
		assertThat(second.output()).contains("already running");
		assertThat(firstResult.exit()).as(firstResult.output()).isZero();
		assertThat(stored()).hasSize(1);

		Path lock = Files.createDirectories(temp.resolve("lock"));
		Files.writeString(lock.resolve("pid"), "999999\n");
		Result afterStale = backup(Map.of());
		assertThat(afterStale.exit()).as(afterStale.output()).isZero();
		assertThat(afterStale.output()).contains("stale lock");
	}

	@Test
	@DisplayName("backup: leftovers of a crashed run are cleaned up")
	void leftoversAreRemoved() throws Exception {
		Files.writeString(backups.resolve(".controlweb-20260101T000000Z.dump.age.partial"), "half a file");
		Files.createDirectories(backups.resolve(".work.abc123"));

		Result result = backup(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(everythingInBackupDir()).hasSize(1);
	}

	@Test
	@DisplayName("backup: the plaintext dump is never written to a file by the script")
	void noPlaintextOnDisk() throws IOException {
		String script = Files.readString(Path.of("deploy/backup/backup.sh"));

		assertThat(script).contains("| age --recipient").contains("> \"$PARTIAL\"");
		assertThat(script).doesNotContain("--file").doesNotContain("> \"$WORK/dump").doesNotContain("> \"$BACKUP_DIR/dump");
	}

	// ======================================================================= restore-test.sh

	private void recentCopy() throws IOException {
		oldCopy("20261009T041500Z", 0);
	}

	private List<String> psqlStatements() throws IOException {
		return calls().lines().filter(line -> line.startsWith("psql[")).toList();
	}

	@Test
	@DisplayName("restore test: restores a fresh dump into controlweb_restore_test, compares, and drops the temporary database")
	void restoreTestSucceeds() throws Exception {
		recentCopy();

		Result result = run(Map.of(), "restore-test.sh");

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(state("last-restore-test")).contains("OK");
		List<String> statements = psqlStatements().stream().filter(line -> line.contains("DATABASE")).toList();
		assertThat(statements).hasSize(3);
		assertThat(statements.get(0)).contains("DROP DATABASE IF EXISTS controlweb_restore_test"); // a leftover of a crashed run
		assertThat(statements.get(1)).contains("CREATE DATABASE controlweb_restore_test");
		assertThat(statements.get(2)).contains("DROP DATABASE IF EXISTS controlweb_restore_test"); // always
		assertThat(fakeState.resolve("test-db-exists")).doesNotExist();
		assertThat(calls()).contains("pg_restore into controlweb_restore_test").contains("--exit-on-error");
		assertThat(Files.readAllBytes(fakeState.resolve("restored-controlweb_restore_test.bin"))).isEqualTo(dump);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAKE_RESTORE_FAIL", "FAKE_RESTORED_COUNT=0", "FAKE_RESTORED_FLYWAY=2", "FAKE_DUMP_FAIL" })
	@DisplayName("restore test: whatever fails (restore error, missing rows, different Flyway history, dump error), the temporary database is dropped and the run fails")
	void restoreTestAlwaysCleansUp(String problem) throws Exception {
		recentCopy();
		String[] parts = problem.split("=");
		Map<String, String> fault = Map.of(parts[0], parts.length > 1 ? parts[1] : "1");

		Result result = run(fault, "restore-test.sh");

		assertThat(result.exit()).as(result.output()).isEqualTo(1);
		assertThat(result.output()).contains("RESTORE TEST FAILED");
		assertThat(fakeState.resolve("test-db-exists")).as("the temporary database is gone").doesNotExist();
		List<String> statements = psqlStatements().stream().filter(line -> line.contains("DATABASE")).toList();
		assertThat(statements.get(statements.size() - 1)).contains("DROP DATABASE IF EXISTS controlweb_restore_test");
		assertThat(state("last-restore-test")).contains("FAILED");
	}

	@Test
	@DisplayName("restore test: a leftover temporary database from a crashed run does not break the next run")
	void leftoverDatabaseIsDropped() throws Exception {
		recentCopy();
		Files.writeString(fakeState.resolve("test-db-exists"), "x");

		Result result = run(Map.of(), "restore-test.sh");

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(fakeState.resolve("test-db-exists")).doesNotExist();
	}

	@Test
	@DisplayName("restore test: every DROP and CREATE names controlweb_restore_test and runs against the 'postgres' database, never the real one")
	void onlyTheTestDatabaseIsTouched() throws Exception {
		recentCopy();
		run(Map.of("FAKE_RESTORE_FAIL", "1"), "restore-test.sh");

		List<String> changes = psqlStatements().stream().filter(s -> s.contains("DROP DATABASE") || s.contains("CREATE DATABASE")).toList();
		assertThat(changes).hasSizeGreaterThanOrEqualTo(2);
		for (String statement : changes) {
			assertThat(statement).startsWith("psql[postgres]: ").contains("controlweb_restore_test");
		}
	}

	@Test
	@DisplayName("restore test: no stored backup, or a stale one (older than 26 h): fails without even creating the database")
	void storedBackupIsChecked() throws Exception {
		Result none = run(Map.of(), "restore-test.sh");
		oldCopy("20260901T000000Z", 2);
		Result stale = run(Map.of(), "restore-test.sh");

		assertThat(none.output()).contains("no stored backup");
		assertThat(stale.output()).contains("hours old");
		assertThat(List.of(none.exit(), stale.exit())).containsOnly(1);
		assertThat(calls()).doesNotContain("CREATE DATABASE");
	}

	// ======================================================================= restore.sh

	private Path identity() throws IOException {
		Path identity = temp.resolve("identity.txt");
		Files.writeString(identity, "# created: test\n# public key: " + RECIPIENT + "\nAGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ\n");
		return identity;
	}

	private Path storedCopy() throws IOException {
		Path copy = backups.resolve("controlweb-20261009T041500Z.dump.age");
		byte[] body = dump;
		byte[] content = new byte[HEADER.length() + 1 + body.length];
		System.arraycopy((HEADER + "\n").getBytes(StandardCharsets.UTF_8), 0, content, 0, HEADER.length() + 1);
		System.arraycopy(body, 0, content, HEADER.length() + 1, body.length);
		Files.write(copy, content);
		return copy;
	}

	private Result restore(Map<String, String> extra, String... arguments) throws Exception {
		return run(extra, "restore.sh", arguments);
	}

	@Test
	@DisplayName("restore: without --yes it only says what it would do (exit 2) and changes nothing")
	void restoreNeedsYes() throws Exception {
		Path copy = storedCopy();

		Result result = restore(Map.of(), "--identity", slashes(identity()), "--allow-disk-identity", slashes(copy));

		assertThat(result.exit()).as(result.output()).isEqualTo(2);
		assertThat(result.output()).contains("Would restore").contains("--yes");
		assertThat(calls()).doesNotContain("stopped").doesNotContain("pg_restore into");
		assertThat(stored()).hasSize(1);
	}

	@Test
	@DisplayName("restore: with --yes it takes a safety backup, stops the application, restores all-or-nothing and leaves the application stopped")
	void restoreWorks() throws Exception {
		Path copy = storedCopy();

		Result result = restore(Map.of(), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", slashes(copy));

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(stored()).as("the safety backup of the current database was taken").hasSize(2);
		String calls = calls();
		assertThat(calls.indexOf("stopped app-container")).isPositive().isLessThan(calls.indexOf("pg_restore into controlweb"));
		assertThat(calls).contains("--clean --if-exists").contains("--exit-on-error").contains("--single-transaction");
		assertThat(Files.readAllBytes(fakeState.resolve("restored-controlweb.bin"))).isEqualTo(dump);
		assertThat(result.output()).contains("STOPPED").contains("rollback --force");
	}

	@Test
	@DisplayName("restore: a wrong identity or an unreadable archive is detected BEFORE anything is stopped, saved or changed")
	void badIdentityChangesNothing() throws Exception {
		Path copy = storedCopy();

		Result wrongKey = restore(Map.of("FAKE_AGE_DECRYPT_FAIL", "1"), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", slashes(copy));
		Result notAnArchive = restore(Map.of("FAKE_LIST_FAIL", "1"), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", slashes(copy));

		assertThat(wrongKey.output()).contains("cannot be decrypted");
		assertThat(notAnArchive.output()).contains("cannot be decrypted");
		assertThat(List.of(wrongKey.exit(), notAnArchive.exit())).containsOnly(1);
		assertThat(calls()).doesNotContain("stopped").doesNotContain("pg_restore into");
		assertThat(stored()).as("no safety backup either").hasSize(1);
	}

	@Test
	@DisplayName("restore: an identity file lying on a disk is refused unless --allow-disk-identity; a file that is not an age identity too")
	void identityChecks() throws Exception {
		Path copy = storedCopy();
		Path notAnIdentity = temp.resolve("not-identity.txt");
		Files.writeString(notAnIdentity, "just some text\n");

		Result onDisk = restore(Map.of("RAM_FS_TYPES", "no-such-filesystem"), "--identity", slashes(identity()), "--yes", slashes(copy));
		Result wrongFile = restore(Map.of(), "--identity", slashes(notAnIdentity), "--allow-disk-identity", "--yes", slashes(copy));

		assertThat(onDisk.output()).contains("on a disk");
		assertThat(wrongFile.output()).contains("not an age identity");
		assertThat(List.of(onDisk.exit(), wrongFile.exit())).containsOnly(1);
		assertThat(calls()).doesNotContain("stopped");
	}

	@Test
	@DisplayName("restore: if pg_restore fails the run fails, says the database is as before (single transaction) and the application stays stopped")
	void restoreFailure() throws Exception {
		Path copy = storedCopy();

		Result result = restore(Map.of("FAKE_RESTORE_FAIL", "1"), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", slashes(copy));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("restore FAILED").contains("single transaction").contains("still stopped");
		assertThat(calls()).contains("stopped app-container");
		assertThat(stored()).as("the safety backup stays").hasSize(2);
	}

	@Test
	@DisplayName("restore: --target-db leaves the application running; --no-safety-backup skips the safety copy; the test database name is reserved")
	void options() throws Exception {
		Path copy = storedCopy();

		Result other = restore(Map.of(), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", "--no-safety-backup",
				"--target-db", "scratch", slashes(copy));
		Result reserved = restore(Map.of(), "--identity", slashes(identity()), "--allow-disk-identity", "--yes", "--target-db",
				"controlweb_restore_test", slashes(copy));

		assertThat(other.exit()).as(other.output()).isZero();
		assertThat(calls()).doesNotContain("stopped");
		assertThat(Files.readAllBytes(fakeState.resolve("restored-scratch.bin"))).isEqualTo(dump);
		assertThat(stored()).as("--no-safety-backup").hasSize(1);
		assertThat(reserved.exit()).isEqualTo(1);
		assertThat(reserved.output()).contains("reserved");
	}

	// ======================================================================= the identity file is destroyed on every way out

	static Stream<Arguments> everyWayOut() {
		return Stream.of(
				Arguments.of("success", Map.of(), List.of("--yes"), 0),
				Arguments.of("the restore fails", Map.of("FAKE_RESTORE_FAIL", "1"), List.of("--yes"), 1),
				Arguments.of("wrong key", Map.of("FAKE_AGE_DECRYPT_FAIL", "1"), List.of("--yes"), 1),
				Arguments.of("not a ControlWeb archive", Map.of("FAKE_LIST_FAIL", "1"), List.of("--yes"), 1),
				Arguments.of("no --yes (it only explains)", Map.of(), List.<String>of(), 2),
				Arguments.of("the safety backup fails", Map.of("FAKE_DUMP_FAIL", "1"), List.of("--yes"), 1),
				Arguments.of("database container down", Map.of("FAKE_DB_DOWN", "1"), List.of("--yes"), 1),
				Arguments.of("a mistyped option", Map.of(), List.of("--bogus"), 1));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("everyWayOut")
	@DisplayName("restore: the identity file is destroyed WHATEVER the outcome (success, any failure, a refused option, only explaining)")
	void identityIsDestroyedWhatEverHappens(String name, Map<String, String> fault, List<String> options, int expectedExit) throws Exception {
		Path copy = storedCopy();
		Path identity = identity();
		List<String> arguments = new ArrayList<>(List.of("--identity", slashes(identity), "--delete-identity", "--allow-disk-identity"));
		arguments.addAll(options);
		arguments.add(slashes(copy));

		Result result = restore(fault, arguments.toArray(String[]::new));

		assertThat(result.exit()).as(result.output()).isEqualTo(expectedExit);
		assertThat(identity).as("the identity file must be gone after: " + name).doesNotExist();
		assertThat(result.output()).contains("was destroyed");
	}

	@Test
	@DisplayName("restore: an identity in RAM is destroyed by default (no flag needed); an identity on a DISK is never deleted unasked")
	void defaultsToDeletingOnlyWhatLivesInRam() throws Exception {
		Path copy = storedCopy();
		Path inRam = identity();
		Process probe = new ProcessBuilder(BASH, "-c",
				"findmnt -no FSTYPE -T \"$1\" 2>/dev/null || stat -f -c %T \"$1\" 2>/dev/null || echo unknown", "_", slashes(inRam))
				.redirectErrorStream(true).start();
		String filesystem = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		probe.waitFor(30, TimeUnit.SECONDS);

		// the test's temporary folder is declared to be "RAM": the script treats the file as such and destroys it
		Result asRam = restore(Map.of("RAM_FS_TYPES", filesystem), "--identity", slashes(inRam), "--yes", "--no-safety-backup", slashes(copy));
		assertThat(asRam.exit()).as(asRam.output()).isZero();
		assertThat(inRam).doesNotExist();

		// the same folder is NOT RAM and no --delete-identity: with --allow-disk-identity the run works and the file stays
		Path onDisk = identity();
		Result asDisk = restore(Map.of("RAM_FS_TYPES", "no-such-filesystem"), "--identity", slashes(onDisk), "--allow-disk-identity", "--yes",
				"--no-safety-backup", slashes(copy));
		assertThat(asDisk.exit()).as(asDisk.output()).isZero();
		assertThat(onDisk).as("a file on a disk is never deleted unless --delete-identity is given").exists();
		assertThat(asDisk.output()).doesNotContain("was destroyed");
	}

	@Test
	@DisplayName("restore: Ctrl-C (SIGINT to the whole process group) in the middle of the restore still destroys the identity file")
	void identityIsDestroyedWhenInterrupted() throws Exception {
		Path copy = storedCopy();
		Path identity = identity();
		Path output = temp.resolve("interrupted-output.txt");
		String script = "set -m; bash deploy/backup/restore.sh --identity '" + slashes(identity) + "' --delete-identity --allow-disk-identity --yes "
				+ "--no-safety-backup '" + slashes(copy) + "' > '" + slashes(output) + "' 2>&1 & pid=$!; "
				+ "for i in $(seq 1 120); do [ -e '" + slashes(fakeState.resolve("restore-started")) + "' ] && break; sleep 0.5; done; "
				+ "sleep 1; kill -INT -$pid; wait $pid; echo \"exit=$?\"";
		ProcessBuilder builder = new ProcessBuilder(BASH, "-c", script).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().putAll(environment(Map.of("FAKE_RESTORE_DELAY", "120")));
		Result outcome = finish(builder.start());

		assertThat(Files.exists(fakeState.resolve("restore-started"))).as("the restore had started").isTrue();
		assertThat(outcome.output()).as("the script died of the interruption").contains("exit=130");
		assertThat(identity).as("the identity file must be destroyed after Ctrl-C").doesNotExist();
		assertThat(Files.readString(output)).contains("was destroyed");
	}

	@Test
	@DisplayName("restore: an identity file readable by others (mode other than 600) is refused, and still destroyed")
	void looseModeIsRefused() throws Exception {
		assumeTrue(!windows(), "file modes are not meaningful on Windows");
		Path copy = storedCopy();
		Path identity = identity();
		Files.setPosixFilePermissions(identity, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

		Result result = restore(Map.of("IDENTITY_MODE_CHECK", "yes"), "--identity", slashes(identity), "--delete-identity", "--allow-disk-identity", "--yes",
				slashes(copy));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("readable by others");
		assertThat(identity).doesNotExist();
		assertThat(calls()).doesNotContain("stopped").doesNotContain("pg_restore into");
	}

	@Test
	@DisplayName("restore: without --identity it only explains what it would do (exit 2) and needs no key, no docker, nothing")
	void planWithoutKey() throws Exception {
		Path copy = storedCopy();

		Result result = restore(Map.of(), slashes(copy));

		assertThat(result.exit()).as(result.output()).isEqualTo(2);
		assertThat(result.output()).contains("Would restore").contains("--identity FILE --yes");
		assertThat(calls()).isEmpty();
	}
}
