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
 * The systemd units of the egress firewall and deploy.sh's refusal to deploy without it. The key property: the firewall unit is
 * ordered BEFORE Docker but is never a hard dependency of it (or the other way round), so a failing or stopped firewall cannot stop
 * Docker, and with it Rotten Odds.
 */
class FirewallUnitsGuardianTest {

	private static final Path DIR = Path.of("deploy/firewall");

	private static List<String> lines(String name) throws IOException {
		return Files.readAllLines(DIR.resolve(name)).stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toList());
	}

	@Test
	@DisplayName("the firewall starts before Docker (Before=docker.service) and is a plain oneshot that stays 'active' while the rules exist")
	void startsBeforeDocker() throws IOException {
		List<String> unit = lines("controlweb-firewall.service");

		assertThat(unit).contains("Before=docker.service", "Type=oneshot", "RemainAfterExit=yes", "ExecStart=/usr/local/sbin/controlweb-firewall start",
				"ExecStop=/usr/local/sbin/controlweb-firewall stop", "WantedBy=multi-user.target");
	}

	@Test
	@DisplayName("no hard dependency between the firewall and Docker in either direction: no Requires, Requisite, BindsTo, PartOf, Conflicts, RequiredBy, BoundBy, OnFailure on docker or on each other")
	void neverAHardDependencyOfDocker() throws IOException {
		for (String name : List.of("controlweb-firewall.service", "controlweb-firewall-guard.service")) {
			assertThat(lines(name)).as(name).noneMatch(line -> line.matches("(Requires|Requisite|BindsTo|PartOf|Conflicts|RequiredBy|BoundBy|OnFailure|Upholds|PropagatesStopTo)=.*"));
		}
		assertThat(lines("controlweb-firewall.service")).noneMatch(line -> line.matches("(Wants|After)=.*docker.*"));
		assertThat(Files.readString(DIR.resolve("install.sh"))).doesNotContain("docker.service.d").doesNotContain("override");
	}

	@Test
	@DisplayName("the guard runs after Docker and the firewall, is not wanted by Docker, only runs 'guard', and a failed firewall start also tries to guard (ExecStopPost with '-')")
	void guardUnit() throws IOException {
		List<String> guard = lines("controlweb-firewall-guard.service");

		assertThat(guard).contains("After=docker.service controlweb-firewall.service", "Type=oneshot", "ExecStart=/usr/local/sbin/controlweb-firewall guard", "WantedBy=multi-user.target");
		assertThat(guard).noneMatch(line -> line.startsWith("Before="));
		assertThat(lines("controlweb-firewall.service")).contains("ExecStopPost=-/usr/local/sbin/controlweb-firewall guard");
	}

	@Test
	@DisplayName("units keep their hardening and write only to /run (the xtables lock); no capability or module restriction that would break iptables")
	void hardening() throws IOException {
		for (String name : List.of("controlweb-firewall.service", "controlweb-firewall-guard.service")) {
			assertThat(lines(name)).as(name).contains("NoNewPrivileges=yes", "PrivateTmp=yes", "ProtectHome=yes", "ProtectSystem=strict", "ReadWritePaths=/run", "RestrictSUIDSGID=yes");
			assertThat(lines(name)).as(name).noneMatch(line -> line.startsWith("CapabilityBoundingSet") || line.startsWith("ProtectKernelModules") || line.startsWith("PrivateNetwork"));
		}
	}

	@Test
	@DisplayName("install.sh installs exactly the script and two units, never starts or enables anything, and does not create the configuration")
	void installerIsConservative() throws IOException {
		String install = Files.readString(DIR.resolve("install.sh"));

		assertThat(install).contains("UNITS=\"controlweb-firewall.service controlweb-firewall-guard.service\"").contains("-m 755").contains("-m 644");
		assertThat(install).doesNotContain("\"$SYSTEMCTL\" enable").doesNotContain("\"$SYSTEMCTL\" start").doesNotContain("\"$SYSTEMCTL\" restart").doesNotContain("firewall.conf <").doesNotContain("> \"$ETC_DIR/firewall.conf");
	}

	@Test
	@DisplayName("deploy.sh refuses to deploy or roll back while the firewall unit is not active, before changing anything")
	void deployRefusesWithoutFirewall() throws IOException {
		String deploy = Files.readString(Path.of("deploy/deploy.sh"));

		assertThat(deploy).contains("FIREWALL_UNIT=${FIREWALL_UNIT-controlweb-firewall.service}").contains("systemctl is-active --quiet \"$FIREWALL_UNIT\"")
				.contains("the application must not run without it");
		int rollback = deploy.indexOf("cmd_rollback() {");
		int deployCmd = deploy.indexOf("cmd_deploy() {");
		assertThat(deploy.substring(rollback, rollback + 300)).contains("check_firewall");
		assertThat(deploy.indexOf("check_firewall", deployCmd)).isGreaterThan(deployCmd).isLessThan(deploy.indexOf("docker load", deployCmd));
	}
}
