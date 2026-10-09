package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** deploy/firewall/install.sh on a fake "root" in a temporary tree, with fake systemctl/systemd-analyze. Linux/macOS only (POSIX modes); runs in CI. */
class InstallFirewallScriptTest {

	@TempDir
	Path temp;

	private Path sbin;
	private Path systemd;
	private Path etc;
	private Path bin;
	private Path log;

	private record Result(int exit, String output) {
	}

	@BeforeEach
	void world() throws Exception {
		assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("windows"), "POSIX permissions are needed");
		sbin = temp.resolve("usr/local/sbin");
		systemd = temp.resolve("etc/systemd/system");
		etc = temp.resolve("etc/controlweb");
		bin = Files.createDirectories(temp.resolve("bin"));
		log = temp.resolve("calls.log");
		fake("systemctl", "echo \"systemctl $*\" >> '" + log + "'\nexit ${FAKE_SYSTEMCTL_EXIT:-0}\n");
		fake("systemd-analyze", "echo \"systemd-analyze $*\" >> '" + log + "'\nexit ${FAKE_ANALYZE_EXIT:-0}\n");
	}

	private void fake(String name, String body) throws IOException {
		ScriptFixtures.write(bin.resolve(name), "#!/bin/sh\n" + body);
	}

	private String run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		process.waitFor(30, TimeUnit.SECONDS);
		return output;
	}

	private Result install(Map<String, String> extra) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("ALLOW_NON_ROOT", "1");
		environment.put("OWNER", run("id", "-un") + ":" + run("id", "-gn"));
		environment.put("CHECK_PARENTS", "no");
		environment.put("SBIN_DIR", sbin.toString());
		environment.put("SYSTEMD_DIR", systemd.toString());
		environment.put("ETC_DIR", etc.toString());
		environment.put("SYSTEMCTL", bin.resolve("systemctl").toString());
		environment.put("SYSTEMD_ANALYZE", bin.resolve("systemd-analyze").toString());
		environment.putAll(extra);
		ProcessBuilder builder = new ProcessBuilder("bash", "deploy/firewall/install.sh").redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().putAll(environment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), output);
	}

	private static String mode(Path path) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
	}

	@Test
	@DisplayName("install: script 0755 and units 0644, identical to the repository; daemon-reload; nothing started or enabled; no configuration created")
	void installs() throws Exception {
		Result result = install(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(mode(sbin.resolve("controlweb-firewall"))).isEqualTo("rwxr-xr-x");
		assertThat(Files.readString(sbin.resolve("controlweb-firewall"))).isEqualTo(Files.readString(Path.of("deploy/firewall/controlweb-firewall.sh")));
		for (String unit : List.of("controlweb-firewall.service", "controlweb-firewall-guard.service")) {
			assertThat(mode(systemd.resolve(unit))).isEqualTo("rw-r--r--");
			assertThat(Files.readString(systemd.resolve(unit))).isEqualTo(Files.readString(Path.of("deploy/firewall", unit)));
		}
		assertThat(etc.resolve("firewall.conf")).as("the public address is the owner's to write").doesNotExist();
		assertThat(Files.readString(log)).contains("systemd-analyze verify controlweb-firewall.service controlweb-firewall-guard.service").contains("systemctl daemon-reload")
				.doesNotContain("enable").doesNotContain("start").doesNotContain("restart");
		assertThat(result.output()).contains("NOT started and NOT enabled").contains("PUBLIC_IP=");
	}

	@Test
	@DisplayName("install: repeating is safe and puts a loosened script or unit right again")
	void repeatable() throws Exception {
		install(Map.of());
		Files.setPosixFilePermissions(sbin.resolve("controlweb-firewall"), PosixFilePermissions.fromString("rwxrwxrwx"));
		Files.setPosixFilePermissions(systemd.resolve("controlweb-firewall.service"), PosixFilePermissions.fromString("rw-rw-rw-"));

		Result result = install(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(mode(sbin.resolve("controlweb-firewall"))).isEqualTo("rwxr-xr-x");
		assertThat(mode(systemd.resolve("controlweb-firewall.service"))).isEqualTo("rw-r--r--");
	}

	@Test
	@DisplayName("install: a configuration writable by others fails the install; systemd-analyze or daemon-reload failing fails it too; parents must be root-owned")
	void failuresAreLoud() throws Exception {
		Files.createDirectories(etc);
		Files.writeString(etc.resolve("firewall.conf"), "PUBLIC_IP=93.184.216.34\n");
		Files.setPosixFilePermissions(etc.resolve("firewall.conf"), PosixFilePermissions.fromString("rw-rw-rw-"));
		Result loose = install(Map.of());
		assertThat(loose.exit()).isEqualTo(1);
		assertThat(loose.output()).contains("must belong to root");
		Files.setPosixFilePermissions(etc.resolve("firewall.conf"), PosixFilePermissions.fromString("rw-r--r--"));

		assertThat(install(Map.of("FAKE_ANALYZE_EXIT", "1")).output()).contains("systemd-analyze verify rejected");
		assertThat(install(Map.of("FAKE_SYSTEMCTL_EXIT", "1")).output()).contains("daemon-reload failed");
		Result parents = install(Map.of("CHECK_PARENTS", "yes"));
		assertThat(parents.exit()).isEqualTo(1);
		assertThat(parents.output()).contains("can be changed by somebody other than root");
	}
}
