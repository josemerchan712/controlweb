package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/backup/install.sh on a fake "root" (the current user) inside a temporary tree: the files and folders get the intended
 * owner and mode, repeating is safe, a group-writable script is put right, and a private key in the recipient file is flagged.
 * Linux/macOS only: Windows has no POSIX modes (the logic was also run by hand in WSL).
 */
class InstallScriptTest {

	@TempDir
	Path temp;

	private Path lib;
	private Path etc;
	private Path backups;
	private Path state;
	private Path bin;
	private Path systemd;
	private Path sudoers;
	private Path log;

	private record Result(int exit, String output) {
	}

	@BeforeEach
	void world() throws Exception {
		assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("windows"), "POSIX permissions are needed");
		lib = temp.resolve("usr/local/lib/controlweb");
		etc = temp.resolve("etc/controlweb");
		backups = temp.resolve("var/backups/controlweb");
		state = temp.resolve("var/lib/controlweb-backup");
		bin = Files.createDirectories(temp.resolve("bin"));
		Files.writeString(bin.resolve("age"), "#!/bin/sh\nexit 0\n");
		bin.resolve("age").toFile().setExecutable(true);
		systemd = temp.resolve("etc/systemd/system");
		sudoers = temp.resolve("etc/sudoers.d");
		log = temp.resolve("calls.log");
		// fakes: the real visudo/systemctl/systemd-analyze need root and a real system; here we check how install.sh REACTS to their answers
		// (the real tools are run against the real files by hand, see docs/DESPLIEGUE.md)
		fake("visudo", "echo \"visudo $*\" >> '" + log + "'\n"
				+ "target=${2:-}\n"
				+ "case \"${FAKE_VISUDO_FAIL:-}\" in all) exit 1;; new) case \"$target\" in *.new) exit 1;; esac;; check) [ \"$1\" = -c ] && exit 1;; esac\n"
				+ "exit 0\n");
		fake("systemctl", "echo \"systemctl $*\" >> '" + log + "'\nexit ${FAKE_SYSTEMCTL_EXIT:-0}\n");
		fake("systemd-analyze", "echo \"systemd-analyze $*\" >> '" + log + "'\nexit ${FAKE_ANALYZE_EXIT:-0}\n");
	}

	private void fake(String name, String body) throws IOException {
		Path file = bin.resolve(name);
		Files.writeString(file, "#!/bin/sh\n" + body);
		file.toFile().setExecutable(true);
	}

	private String calls() throws IOException {
		return Files.exists(log) ? Files.readString(log) : "";
	}

	private String command(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		process.waitFor(30, TimeUnit.SECONDS);
		return output;
	}

	private Result install(Map<String, String> extra) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("PATH", bin + ":" + System.getenv("PATH"));
		environment.put("ALLOW_NON_ROOT", "1");
		environment.put("OWNER", command("id", "-un") + ":" + command("id", "-gn"));
		environment.put("CHECK_PARENTS", "no");
		environment.put("LIB_DIR", lib.toString());
		environment.put("ETC_DIR", etc.toString());
		environment.put("BACKUP_DIR", backups.toString());
		environment.put("STATE_DIR", state.toString());
		environment.put("SYSTEMD_DIR", systemd.toString());
		environment.put("SUDOERS_DIR", sudoers.toString());
		environment.put("VISUDO", bin.resolve("visudo").toString());
		environment.put("SYSTEMCTL", bin.resolve("systemctl").toString());
		environment.put("SYSTEMD_ANALYZE", bin.resolve("systemd-analyze").toString());
		environment.putAll(extra);
		ProcessBuilder builder = new ProcessBuilder("bash", "deploy/backup/install.sh").redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().putAll(environment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), output);
	}

	private static String mode(Path path) throws IOException {
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
		return java.nio.file.attribute.PosixFilePermissions.toString(permissions);
	}

	@Test
	@DisplayName("install: scripts 0755, backup folder 0700, state and etc folders 0755; repeating is safe")
	void installsWithTheRightModes() throws Exception {
		Result first = install(Map.of());
		Result second = install(Map.of());

		assertThat(first.exit()).as(first.output()).isZero();
		assertThat(second.exit()).as(second.output()).isZero();
		for (String script : List.of("common.sh", "backup.sh", "restore.sh", "restore-test.sh")) {
			assertThat(mode(lib.resolve(script))).isEqualTo("rwxr-xr-x");
		}
		assertThat(mode(backups)).isEqualTo("rwx------");
		assertThat(mode(state)).isEqualTo("rwxr-xr-x");
		assertThat(mode(etc)).isEqualTo("rwxr-xr-x");
		assertThat(first.output()).contains("Next: generate the age key pair").contains("installed");
	}

	@Test
	@DisplayName("install: a script that became group-writable is put right by installing again")
	void groupWritableScriptIsFixed() throws Exception {
		install(Map.of());
		Files.setPosixFilePermissions(lib.resolve("backup.sh"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x"));

		Result result = install(Map.of());

		assertThat(result.exit()).isZero();
		assertThat(mode(lib.resolve("backup.sh"))).isEqualTo("rwxr-xr-x");
	}

	@Test
	@DisplayName("install: a PRIVATE key found in the recipient file is flagged and the install fails")
	void privateKeyIsFlagged() throws Exception {
		install(Map.of());
		Files.writeString(etc.resolve("backup-recipient.txt"), "AGE-SECRET-KEY-1ABC\n");
		Files.setPosixFilePermissions(etc.resolve("backup-recipient.txt"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

		Result result = install(Map.of());

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("PRIVATE key");
	}

	@Test
	@DisplayName("install: every folder above the scripts must be root-owned and not writable by others (a user-owned tree fails)")
	void parentFoldersAreChecked() throws Exception {
		Result result = install(Map.of("CHECK_PARENTS", "yes"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("can be changed by somebody other than root");
	}

	@Test
	@DisplayName("install: without age installed it stops before copying anything")
	void needsAge() throws Exception {
		Files.delete(bin.resolve("age"));
		Result result = install(Map.of("PATH", "/usr/bin:/bin"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("age is not installed");
		assertThat(lib).doesNotExist();
	}

	@Test
	@DisplayName("install: units 0644 and the sudoers rule 0440 are installed, daemon-reload runs, and the timers are NOT enabled")
	void installsUnitsAndRule() throws Exception {
		Result result = install(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		for (String unit : List.of("controlweb-backup.service", "controlweb-backup.timer", "controlweb-restore-test.service", "controlweb-restore-test.timer")) {
			assertThat(mode(systemd.resolve(unit))).isEqualTo("rw-r--r--");
			assertThat(Files.readString(systemd.resolve(unit))).isEqualTo(Files.readString(Path.of("deploy/backup/systemd", unit)));
		}
		assertThat(mode(sudoers.resolve("controlweb-backup"))).isEqualTo("r--r-----");
		assertThat(sudoers.resolve("controlweb-backup.new")).doesNotExist();
		assertThat(Files.readString(sudoers.resolve("controlweb-backup"))).isEqualTo(Files.readString(Path.of("deploy/backup/controlweb-backup.sudoers")));
		assertThat(calls()).contains("visudo -cf").contains("visudo -c\n").contains("systemd-analyze verify controlweb-backup.service").contains("systemctl daemon-reload")
				.doesNotContain("enable").doesNotContain("start");
		assertThat(result.output()).contains("NOT enabled yet");
	}

	@Test
	@DisplayName("install: a rule that visudo rejects is never installed (nothing in /etc/sudoers.d, nothing copied)")
	void rejectedRuleIsNotInstalled() throws Exception {
		Result result = install(Map.of("FAKE_VISUDO_FAIL", "all"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("visudo does not accept");
		assertThat(sudoers).doesNotExist();
		assertThat(lib).doesNotExist();
		assertThat(systemd).doesNotExist();
	}

	@Test
	@DisplayName("install: if visudo rejects the installed copy, the file never becomes active and the previous rule stays")
	void rejectedCopyNeverBecomesActive() throws Exception {
		install(Map.of());
		String before = Files.readString(sudoers.resolve("controlweb-backup"));
		Files.writeString(sudoers.resolve("controlweb-backup.new"), "stale leftover\n");

		Result result = install(Map.of("FAKE_VISUDO_FAIL", "new"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("was NOT activated");
		assertThat(sudoers.resolve("controlweb-backup.new")).doesNotExist();
		assertThat(Files.readString(sudoers.resolve("controlweb-backup"))).isEqualTo(before);
	}

	@Test
	@DisplayName("install: if the whole sudoers configuration fails visudo -c, or systemd rejects the units, it fails loudly")
	void failingValidationsFailTheInstall() throws Exception {
		Result sudoersBroken = install(Map.of("FAKE_VISUDO_FAIL", "check"));
		Result unitsBroken = install(Map.of("FAKE_ANALYZE_EXIT", "1"));
		Result reloadBroken = install(Map.of("FAKE_SYSTEMCTL_EXIT", "1"));

		assertThat(sudoersBroken.exit()).isEqualTo(1);
		assertThat(sudoersBroken.output()).contains("visudo -c reports an error");
		assertThat(unitsBroken.exit()).isEqualTo(1);
		assertThat(unitsBroken.output()).contains("systemd-analyze verify rejected");
		assertThat(reloadBroken.exit()).isEqualTo(1);
		assertThat(reloadBroken.output()).contains("daemon-reload failed");
	}

	@Test
	@DisplayName("install: a unit or the sudoers file that became writable by others is put right by installing again")
	void looseUnitIsPutRight() throws Exception {
		install(Map.of());
		Files.setPosixFilePermissions(systemd.resolve("controlweb-backup.service"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
		Path rule = sudoers.resolve("controlweb-backup");
		Files.setPosixFilePermissions(rule, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));

		Result result = install(Map.of());

		assertThat(result.exit()).isZero();
		assertThat(mode(systemd.resolve("controlweb-backup.service"))).isEqualTo("rw-r--r--");
		assertThat(mode(rule)).isEqualTo("r--r-----");
	}
}
