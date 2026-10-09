package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/backup/install-reader.sh on a fake "root" inside a temporary tree, with fake useradd/getent/visudo: the restricted key
 * line, the root-owned files and modes, and above all what it REFUSES (private keys, keys with options, other key types, several
 * keys, a bad --from) before changing anything. Linux/macOS only (POSIX modes); it also runs in CI.
 */
class InstallReaderScriptTest {

	private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + "A".repeat(42) + " pc-de-jose";

	@TempDir
	Path temp;

	private Path lib;
	private Path home;
	private Path sudoers;
	private Path bin;
	private Path log;

	private record Result(int exit, String output) {
	}

	@BeforeEach
	void world() throws Exception {
		assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("windows"), "POSIX permissions are needed");
		lib = Files.createDirectories(temp.resolve("usr/local/lib/controlweb"));
		home = temp.resolve("var/lib/cwbackup");
		sudoers = temp.resolve("etc/sudoers.d");
		bin = Files.createDirectories(temp.resolve("bin"));
		log = temp.resolve("calls.log");
		fake("visudo", "echo \"visudo $*\" >> '" + log + "'\ncase \"${FAKE_VISUDO_FAIL:-}\" in all) exit 1;; new) case \"${2:-}\" in *.new) exit 1;; esac;; esac\nexit 0\n");
		fake("useradd", "echo \"useradd $*\" >> '" + log + "'\nexit 0\n");
		fake("getent", "echo \"getent $*\" >> '" + log + "'\n[ \"${FAKE_USER_EXISTS:-}\" = 1 ]\n");
	}

	private void fake(String name, String body) throws IOException {
		ScriptFixtures.write(bin.resolve(name), "#!/bin/sh\n" + body);
	}

	private String calls() throws IOException {
		return Files.exists(log) ? Files.readString(log) : "";
	}

	private Path pubkey(String content) throws IOException {
		Path file = temp.resolve("key.pub");
		Files.writeString(file, content);
		return file;
	}

	private Result install(Map<String, String> extra, String... arguments) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("ALLOW_NON_ROOT", "1");
		environment.put("OWNER", run("id", "-un") + ":" + run("id", "-gn"));
		environment.put("CHECK_PARENTS", "no");
		environment.put("LIB_DIR", lib.toString());
		environment.put("HOME_DIR", home.toString());
		environment.put("SUDOERS_DIR", sudoers.toString());
		environment.put("VISUDO", bin.resolve("visudo").toString());
		environment.put("USERADD", bin.resolve("useradd").toString());
		environment.put("GETENT", bin.resolve("getent").toString());
		environment.putAll(extra);
		java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("bash", "deploy/backup/install-reader.sh"));
		command.addAll(java.util.List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().putAll(environment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), output);
	}

	private String run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		process.waitFor(30, TimeUnit.SECONDS);
		return output;
	}

	private static String mode(Path path) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
	}

	private void assertNothingInstalled() {
		assertThat(home).doesNotExist();
		assertThat(sudoers).doesNotExist();
		assertThat(lib.resolve("serve-backup.sh")).doesNotExist();
	}

	@Test
	@DisplayName("install-reader: the key line is restrict + forced command + the public key; files root-owned with the right modes; account created")
	void installsTheRestrictedKey() throws Exception {
		Result result = install(Map.of(), "--pubkey", pubkey(KEY + "\n").toString());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(Files.readString(home.resolve(".ssh/authorized_keys")))
				.isEqualTo("restrict,command=\"sudo -n " + lib + "/serve-backup.sh\" " + KEY + "\n");
		assertThat(mode(home.resolve(".ssh/authorized_keys"))).isEqualTo("rw-r--r--");
		assertThat(mode(home)).isEqualTo("rwxr-xr-x");
		assertThat(mode(home.resolve(".ssh"))).isEqualTo("rwxr-xr-x");
		assertThat(mode(lib.resolve("serve-backup.sh"))).isEqualTo("rwxr-xr-x");
		assertThat(mode(sudoers.resolve("controlweb-reader"))).isEqualTo("r--r-----");
		assertThat(Files.readString(sudoers.resolve("controlweb-reader"))).isEqualTo(Files.readString(Path.of("deploy/backup/controlweb-reader.sudoers")));
		assertThat(sudoers.resolve("controlweb-reader.new")).doesNotExist();
		assertThat(calls()).contains("useradd --system --user-group --home-dir " + home + " --no-create-home --shell /bin/bash").contains("visudo -cf").contains("visudo -c\n");
	}

	@Test
	@DisplayName("install-reader: --from adds from=\"...\" in front; an existing account is not created again; repeating is safe")
	void fromAndRepeat() throws Exception {
		Path key = pubkey(KEY + "\n");
		Result first = install(Map.of(), "--pubkey", key.toString(), "--from", "203.0.113.7");
		Result second = install(Map.of("FAKE_USER_EXISTS", "1"), "--pubkey", key.toString(), "--from", "203.0.113.7");

		assertThat(first.exit()).as(first.output()).isZero();
		assertThat(second.exit()).as(second.output()).isZero();
		assertThat(Files.readString(home.resolve(".ssh/authorized_keys"))).startsWith("from=\"203.0.113.7\",restrict,command=\"sudo -n ").endsWith(KEY + "\n");
		assertThat(calls().split("useradd", -1)).as("useradd ran once").hasSize(2);
	}

	@Test
	@DisplayName("install-reader: refuses a PRIVATE key, a key with options, other key types, several keys, junk, and installs nothing")
	void refusesBadKeys() throws Exception {
		Map<String, String> bad = new java.util.LinkedHashMap<>();
		bad.put("private key", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n");
		bad.put("options smuggled in the file", "command=\"/bin/sh\" " + KEY + "\n");
		bad.put("permitopen option", "restrict,port-forwarding " + KEY + "\n");
		bad.put("rsa key", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQC" + "A".repeat(60) + " x\n");
		bad.put("two keys", KEY + "\n" + KEY + "\n");
		bad.put("empty", "\n\n");
		bad.put("key with a newline injected command", KEY + "\" ; id #\n");
		bad.put("an age identity", "AGE-SECRET-KEY-1" + "Q".repeat(58) + "\n");
		bad.put("quote in the comment", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + "A".repeat(42) + " a\"b\n");
		for (Map.Entry<String, String> entry : bad.entrySet()) {
			Result result = install(Map.of(), "--pubkey", pubkey(entry.getValue()).toString());
			assertThat(result.exit()).as(entry.getKey() + ": " + result.output()).isEqualTo(1);
			assertNothingInstalled();
		}
		assertThat(calls()).doesNotContain("useradd");
	}

	@Test
	@DisplayName("install-reader: --from accepts addresses/CIDR only; options, names and wildcards are refused")
	void fromIsValidated() throws Exception {
		Path key = pubkey(KEY + "\n");
		for (String from : new String[] { "*", "evil.example.com", "10.0.0.1\",command=\"x", "!10.0.0.1", "10.0.0.*", "" }) {
			Result result = install(Map.of(), "--pubkey", key.toString(), "--from", from);
			assertThat(result.exit()).as("--from '" + from + "': " + result.output()).isEqualTo(1);
			assertNothingInstalled();
		}
		assertThat(install(Map.of(), "--pubkey", key.toString(), "--from", "2001:db8::1").exit()).isZero();
	}

	@Test
	@DisplayName("install-reader: needs --pubkey, a readable file, a known option, and the lib folder created by install.sh")
	void argumentsAreChecked() throws Exception {
		assertThat(install(Map.of()).exit()).isEqualTo(1);
		assertThat(install(Map.of(), "--pubkey", temp.resolve("missing.pub").toString()).output()).contains("does not exist");
		assertThat(install(Map.of(), "--pubkey", pubkey(KEY).toString(), "--root").output()).contains("unknown option");
		Files.delete(lib);
		Result noLib = install(Map.of(), "--pubkey", pubkey(KEY).toString());
		assertThat(noLib.exit()).isEqualTo(1);
		assertThat(noLib.output()).contains("run deploy/backup/install.sh first");
	}

	@Test
	@DisplayName("install-reader: a sudoers rule that visudo rejects is never activated and the previous one stays")
	void visudoGuards() throws Exception {
		Path key = pubkey(KEY + "\n");
		Result rejectedSource = install(Map.of("FAKE_VISUDO_FAIL", "all"), "--pubkey", key.toString());
		assertThat(rejectedSource.exit()).isEqualTo(1);
		assertNothingInstalled();

		assertThat(install(Map.of(), "--pubkey", key.toString()).exit()).isZero();
		String before = Files.readString(sudoers.resolve("controlweb-reader"));
		Result rejectedCopy = install(Map.of("FAKE_VISUDO_FAIL", "new"), "--pubkey", key.toString());
		assertThat(rejectedCopy.exit()).isEqualTo(1);
		assertThat(rejectedCopy.output()).contains("was NOT activated");
		assertThat(Files.readString(sudoers.resolve("controlweb-reader"))).isEqualTo(before);
		assertThat(sudoers.resolve("controlweb-reader.new")).doesNotExist();
	}

	@Test
	@DisplayName("the reader rule is ONE exact command without arguments (plus the no-pty default), for the cwbackup account only")
	void readerRuleIsExact() throws IOException {
		java.util.List<String> rules = Files.readAllLines(Path.of("deploy/backup/controlweb-reader.sudoers")).stream().map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();

		assertThat(rules).containsExactly("Defaults:cwbackup !use_pty", "cwbackup ALL=(root) NOPASSWD: /usr/local/lib/controlweb/serve-backup.sh");
	}
}
