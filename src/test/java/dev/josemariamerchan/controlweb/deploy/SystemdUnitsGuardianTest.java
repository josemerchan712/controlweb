package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guardian for the systemd units and the sudoers rule of the backups (deploy/backup). They are plain text, so they are checked on every
 * OS: what matters is that the rule allows exactly ONE command, that the unit that command starts runs a root-owned script with no
 * arguments, and that the units keep their hardening and their schedule. `visudo -cf` and `systemd-analyze verify` (the real
 * validators) run in install.sh on the server.
 */
class SystemdUnitsGuardianTest {

	private static final Path DIR = Path.of("deploy/backup");
	private static final List<String> HARDENING = List.of("NoNewPrivileges=yes", "PrivateTmp=yes", "PrivateDevices=yes", "ProtectSystem=strict",
			"ProtectHome=yes", "ProtectKernelTunables=yes", "ProtectKernelModules=yes", "ProtectKernelLogs=yes", "ProtectControlGroups=yes",
			"RestrictSUIDSGID=yes", "RestrictRealtime=yes", "LockPersonality=yes");

	/** The non-comment, non-blank lines of a file. */
	private static List<String> lines(Path file) throws IOException {
		return Files.readAllLines(file).stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toList());
	}

	private static List<String> unit(String name) throws IOException {
		return lines(DIR.resolve("systemd").resolve(name));
	}

	@Test
	@DisplayName("sudoers: exactly ONE rule, for jose, one exact command with its arguments, no wildcard, no tag, no ALL command")
	void sudoersRuleIsOneExactCommand() throws IOException {
		List<String> rules = lines(DIR.resolve("controlweb-backup.sudoers"));

		assertThat(rules).containsExactly("jose ALL=(root) NOPASSWD: /usr/bin/systemctl start controlweb-backup.service");
		String rule = rules.get(0);
		assertThat(rule).doesNotContain("*").doesNotContain("?").doesNotContain("[").doesNotContain("\\").doesNotContain(",")
				.doesNotContain("SETENV").doesNotContain("EXEC").doesNotContain("Cmnd_Alias").doesNotContain("(ALL");
		assertThat(rule).doesNotEndWith("ALL");
	}

	@Test
	@DisplayName("sudoers: the unit it starts exists in the repository and deploy.sh asks for exactly that unit")
	void ruleMatchesTheUnitAndDeploy() throws IOException {
		assertThat(DIR.resolve("systemd/controlweb-backup.service")).exists();
		String deploy = Files.readString(Path.of("deploy/deploy.sh"));
		assertThat(deploy).contains("BACKUP_UNIT=${BACKUP_UNIT-controlweb-backup.service}").contains("$SUDO systemctl start \"$BACKUP_UNIT\"");
	}

	@Test
	@DisplayName("backup service: oneshot, root, ONE script with no arguments, no shell, no environment file, no pre/post commands")
	void backupServiceRunsOneFixedScript() throws IOException {
		List<String> service = unit("controlweb-backup.service");

		assertThat(service).contains("Type=oneshot", "User=root", "ExecStart=/usr/local/lib/controlweb/backup.sh");
		assertThat(service).filteredOn(line -> line.startsWith("ExecStart")).hasSize(1);
		assertThat(service).noneMatch(line -> line.startsWith("ExecStartPre") || line.startsWith("ExecStartPost") || line.startsWith("ExecStop")
				|| line.startsWith("ExecReload") || line.startsWith("EnvironmentFile") || line.startsWith("Environment=") || line.startsWith("PassEnvironment")
				|| line.startsWith("WorkingDirectory") || line.startsWith("RootDirectory"));
		assertThat(service).noneMatch(line -> line.contains("/bin/sh") || line.contains("/bin/bash") || line.contains("-c "));
	}

	@Test
	@DisplayName("restore-test service: same shape, runs restore-test.sh from the root-owned folder")
	void restoreTestServiceRunsOneFixedScript() throws IOException {
		List<String> service = unit("controlweb-restore-test.service");

		assertThat(service).contains("Type=oneshot", "User=root", "ExecStart=/usr/local/lib/controlweb/restore-test.sh");
		assertThat(service).filteredOn(line -> line.startsWith("ExecStart")).hasSize(1);
		assertThat(service).noneMatch(line -> line.startsWith("EnvironmentFile") || line.startsWith("Environment=") || line.startsWith("ExecStartPre"));
	}

	@Test
	@DisplayName("both services keep every hardening directive and only write where they must")
	void servicesAreHardened() throws IOException {
		for (String name : List.of("controlweb-backup.service", "controlweb-restore-test.service")) {
			List<String> service = unit(name);
			assertThat(service).as(name).containsAll(HARDENING);
			assertThat(service).as(name).contains("ReadWritePaths=/var/backups/controlweb /var/lib/controlweb-backup /run");
		}
	}

	@Test
	@DisplayName("timers: backup every day 04:15 UTC, restore test on Sundays 05:00 UTC, both persistent, each starts its own service")
	void timersHaveTheAgreedSchedule() throws IOException {
		List<String> backup = unit("controlweb-backup.timer");
		List<String> restore = unit("controlweb-restore-test.timer");

		assertThat(backup).contains("OnCalendar=*-*-* 04:15:00 UTC", "Persistent=true", "Unit=controlweb-backup.service", "WantedBy=timers.target");
		assertThat(restore).contains("OnCalendar=Sun *-*-* 05:00:00 UTC", "Persistent=true", "Unit=controlweb-restore-test.service", "WantedBy=timers.target");
	}

	@Test
	@DisplayName("install.sh installs exactly these units and this rule, and never enables a timer")
	void installerKnowsTheFiles() throws IOException {
		String install = Files.readString(DIR.resolve("install.sh"));

		assertThat(install).contains("UNITS=\"controlweb-backup.service controlweb-backup.timer controlweb-restore-test.service controlweb-restore-test.timer\"")
				.contains("SUDOERS_NAME=controlweb-backup").contains("-m 440").contains("-m 644");
		assertThat(install).doesNotContain("systemctl enable\"").doesNotContain("\"$SYSTEMCTL\" enable").doesNotContain("\"$SYSTEMCTL\" start");
		List<Path> files;
		try (var stream = Files.list(DIR.resolve("systemd"))) {
			files = stream.sorted().toList();
		}
		assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("controlweb-backup.service", "controlweb-backup.timer",
				"controlweb-restore-test.service", "controlweb-restore-test.timer");
	}
}
