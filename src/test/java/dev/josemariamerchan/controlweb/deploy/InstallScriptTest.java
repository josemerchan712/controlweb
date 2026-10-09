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
}
