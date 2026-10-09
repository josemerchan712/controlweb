package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs deploy/deploy.sh for real (real git, real bash) against a fake docker, a fake systemctl and a fake sudo: a real
 * clone, three real commits (the third one adds a database migration), a bare repository as "GitHub". The point is the
 * decisions: first deploy without backup, backup before every later one, automatic rollback when there are no new
 * migrations, NO automatic rollback when there are, and a refusal before anything changes when something is wrong.
 */
@Tag("slow-scripts")
class DeployScriptTest {

	private static final String BASH = bashExecutable();

	/** Built once for the whole class: a bare repository ("GitHub") and a clone of it ("the VPS"). */
	@TempDir
	static Path shared;

	@TempDir
	Path temp;

	private static Path serverTemplate;
	private static String c1;
	private static String c2;
	private static String c3; // adds a migration

	private Path server; // the clone on the "VPS": a fresh copy of the template for every test
	private Path bin;
	private Path state;
	private Path envFile;

	private record Result(int exit, String output) {
	}

	@BeforeAll
	static void buildTheSharedWorld() throws Exception {
		assumeTrue(BASH != null, "bash is not available");
		Path bare = shared.resolve("github.git");
		Files.createDirectories(bare);
		git(bare, "init", "-q", "--bare", "-b", "main");

		Path dev = shared.resolve("dev");
		Files.createDirectories(dev);
		git(dev, "init", "-q", "-b", "main");
		String deployScript = Files.readString(Path.of("deploy/deploy.sh"), StandardCharsets.UTF_8).replace("\r\n", "\n");
		write(dev.resolve("deploy/deploy.sh"), deployScript);
		write(dev.resolve("compose.prod.yml"), "services: {}\n");
		write(dev.resolve(".gitignore"), ".deploy/\n");
		write(dev.resolve("src/main/resources/db/migration/V1__init.sql"), "create table a (id int);\n");
		write(dev.resolve("README.md"), "one\n");
		c1 = commit(dev, "first");
		write(dev.resolve("README.md"), "two\n");
		c2 = commit(dev, "no migration");
		write(dev.resolve("src/main/resources/db/migration/V3__more.sql"), "create table b (id int);\n");
		c3 = commit(dev, "adds a migration");
		git(dev, "remote", "add", "origin", slashes(bare));
		git(dev, "push", "-q", "origin", "main");

		serverTemplate = shared.resolve("server-template");
		git(shared, "clone", "-q", slashes(bare), slashes(serverTemplate));
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

	private static String git(Path directory, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
				"-c", "core.autocrlf=false", "-c", "commit.gpgsign=false"));
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).as("git %s: %s", String.join(" ", arguments), output).isZero();
		return output.strip();
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static String commit(Path dev, String message) throws Exception {
		git(dev, "add", "-A");
		git(dev, "commit", "-q", "-m", message);
		return git(dev, "rev-parse", "--short=12", "HEAD");
	}

	@BeforeEach
	void world() throws Exception {
		server = temp.resolve("server");
		try (var paths = Files.walk(serverTemplate)) {
			for (Path source : (Iterable<Path>) paths::iterator) {
				Files.copy(source, server.resolve(serverTemplate.relativize(source).toString()), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
			}
		}

		bin = Files.createDirectories(temp.resolve("bin"));
		for (String[] stub : new String[][] { { "deploy-docker", "docker" }, { "deploy-systemctl", "systemctl" },
				{ "deploy-sudo", "sudo" } }) {
			ScriptFixtures.copyResource("/fakedocker/" + stub[0] + ".sh", bin, stub[1]);
		}
		state = Files.createDirectories(temp.resolve("fake-state"));
		envFile = temp.resolve("env.prod");
		write(envFile, "DB_PASSWORD=not-used-by-the-fake\n");
	}

	private Path imageFile(String sha) throws Exception {
		Path releases = Files.createDirectories(temp.resolve("releases"));
		Path tar = releases.resolve("controlweb-app-" + sha + ".tar");
		write(tar, "controlweb-app:" + sha + "\n");
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tar)));
		write(releases.resolve(tar.getFileName() + ".sha256"), hash + "  " + tar.getFileName() + "\n");
		return tar;
	}

	private Result run(Map<String, String> extraEnvironment, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of(BASH, slashes(server.resolve("deploy/deploy.sh"))));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(temp.toFile());
		String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
		builder.environment().put("PATH", bin + java.io.File.pathSeparator + path);
		builder.environment().put("FAKE_STATE", slashes(state));
		builder.environment().put("ENV_FILE", slashes(envFile));
		builder.environment().put("HEALTH_TIMEOUT", "12");
		builder.environment().put("POLL_SECONDS", "1");
		builder.environment().put("BACKUP_STATUS_FILE", "");
		builder.environment().putAll(extraEnvironment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the script finished").isTrue();
		return new Result(process.exitValue(), output);
	}

	private Result deploy(String sha, Map<String, String> extraEnvironment) throws Exception {
		// a path relative to where the script is started, exactly as release.ps1 passes it over ssh
		return run(extraEnvironment, sha, temp.relativize(imageFile(sha)).toString().replace('\\', '/'));
	}

	private Result deploy(String sha) throws Exception {
		return deploy(sha, Map.of());
	}

	private String calls() throws IOException {
		return Files.readString(state.resolve("calls.log"));
	}

	private String image(String tag) throws IOException {
		return Files.readAllLines(state.resolve("images")).stream().filter(line -> line.startsWith("controlweb-app:" + tag + " "))
				.map(line -> line.substring(line.indexOf(' ') + 1)).reduce((first, second) -> second).orElse("");
	}

	private String deployState(String name) throws IOException {
		Path file = server.resolve(".deploy/" + name);
		return Files.exists(file) ? Files.readString(file).strip() : "";
	}

	private String head() throws Exception {
		return git(server, "rev-parse", "--short=12", "HEAD");
	}

	// ---- the happy paths ----

	@Test
	@DisplayName("first deploy: no backup (there is nothing to protect), the image becomes 'current', the clone moves to the commit")
	void firstDeploy() throws Exception {
		Result result = deploy(c1);

		assertThat(result.output()).contains("first deploy").contains("deployed " + c1);
		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(calls()).doesNotContain("systemctl start");
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c1);
		assertThat(image("previous")).isEmpty();
		assertThat(deployState("current")).isEqualTo(c1);
		assertThat(head()).isEqualTo(c1);
		assertThat(imageFileStillThere(c1)).as("the uploaded tar is removed after a successful deploy").isFalse();
	}

	private boolean imageFileStillThere(String sha) {
		return Files.exists(temp.resolve("releases/controlweb-app-" + sha + ".tar"));
	}

	@Test
	@DisplayName("second deploy: the backup is taken BEFORE the image is loaded; current becomes previous")
	void secondDeploy() throws Exception {
		assertThat(deploy(c1).exit()).isZero();

		Result result = deploy(c2);

		assertThat(result.exit()).as(result.output()).isZero();
		String calls = calls();
		assertThat(calls).contains("systemctl start controlweb-backup.service");
		assertThat(calls.lastIndexOf("systemctl start")).isLessThan(calls.lastIndexOf("docker load"));
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c2);
		assertThat(image("previous")).isEqualTo("img-controlweb-app:" + c1);
		assertThat(deployState("previous")).isEqualTo(c1);
		assertThat(deployState("current")).isEqualTo(c2);
		assertThat(head()).isEqualTo(c2);
	}

	// ---- failures ----

	@Test
	@DisplayName("a release that never becomes healthy, WITHOUT new migrations: rolled back automatically, exit status 1")
	void automaticRollback() throws Exception {
		assertThat(deploy(c1).exit()).isZero();

		Result result = deploy(c2, Map.of("FAKE_BAD_SHA", c2));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("rolling back automatically").contains("rolled back: " + c1);
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c1);
		assertThat(deployState("current")).isEqualTo(c1);
		assertThat(deployState("previous")).as("nothing older to go back to").isEmpty();
		assertThat(head()).isEqualTo(c1);
		assertThat(calls()).contains("up current=img-controlweb-app:" + c2).contains("up current=img-controlweb-app:" + c1);
	}

	@Test
	@DisplayName("a release that never becomes healthy WITH a new migration: NOT rolled back, the application is stopped, the backup is pointed out")
	void noAutomaticRollbackAcrossMigrations() throws Exception {
		assertThat(deploy(c2).exit()).isZero();

		Result result = deploy(c3, Map.of("FAKE_BAD_SHA", c3));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("NOT rolled back automatically").contains("restore").contains("Flyway only goes forward");
		assertThat(calls()).contains("stop controlweb-app");
		assertThat(calls().split("up current=", -1)).as("one start per deploy, none to restore the previous release").hasSize(3);
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c3);
		assertThat(head()).isEqualTo(c3);
	}

	@Test
	@DisplayName("a first release that is not healthy: the application is stopped, there is nothing to go back to")
	void firstReleaseFails() throws Exception {
		Result result = deploy(c1, Map.of("FAKE_BAD_SHA", c1));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("nothing to go back to");
		assertThat(calls()).contains("stop controlweb-app");
	}

	// ---- refusals: nothing may change ----

	@Test
	@DisplayName("local changes in the clone: refused before touching anything")
	void localChangesAreRefused() throws Exception {
		write(server.resolve("README.md"), "edited on the server\n");

		Result result = deploy(c1);

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("local changes");
		assertThat(calls()).doesNotContain("docker load").doesNotContain("systemctl start");
	}

	@Test
	@DisplayName("egress firewall not active: refused")
	void inactiveFirewallIsRefused() throws Exception {
		Result result = deploy(c1, Map.of("FAKE_FIREWALL", "inactive"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("firewall");
		assertThat(calls()).doesNotContain("docker load");
	}

	@Test
	@DisplayName("a failed pre-deploy backup stops the deploy before anything is loaded, tagged or checked out")
	void failedBackupChangesNothing() throws Exception {
		assertThat(deploy(c1).exit()).isZero();

		Result result = deploy(c2, Map.of("FAKE_BACKUP_FAIL", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("backup failed").contains("nothing was changed");
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c1);
		assertThat(image("controlweb-app:" + c2)).isEmpty();
		assertThat(head()).isEqualTo(c1);
		assertThat(calls().split("docker load", -1)).as("only the first deploy loaded an image").hasSize(2);
	}

	@Test
	@DisplayName("compose.prod.yml that does not accept .env.prod: refused before anything changes")
	void missingVariableIsRefused() throws Exception {
		Result result = deploy(c1, Map.of("FAKE_CONFIG_FAIL", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("does not accept the variables");
		assertThat(calls()).doesNotContain("docker load");
	}

	@Test
	@DisplayName("bad arguments are refused: a corrupted upload, a missing file, a commit that is not pushed")
	void badArguments() throws Exception {
		Path tar = imageFile(c1);
		write(tar, "controlweb-app:" + c1 + "\ncorrupted\n"); // the checksum no longer matches

		Result corrupted = run(Map.of(), c1, "releases/" + tar.getFileName());
		Result missing = run(Map.of(), c1, "releases/nope.tar");
		Result unknownCommit = deploy("0123456789ab");

		assertThat(corrupted.output()).contains("checksum");
		assertThat(missing.output()).contains("does not exist");
		assertThat(unknownCommit.output()).contains("is not in the repository");
		assertThat(List.of(corrupted.exit(), missing.exit(), unknownCommit.exit())).containsOnly(1);
		assertThat(calls()).doesNotContain("docker load");
	}

	// ---- manual rollback and status ----

	@Test
	@DisplayName("manual rollback goes back to the previous release; a second one has nothing to go back to; status tells the truth")
	void manualRollback() throws Exception {
		assertThat(deploy(c1).exit()).isZero();
		assertThat(deploy(c2).exit()).isZero();

		Result status = run(Map.of(), "status");
		assertThat(status.output()).contains("running release : " + c2).contains("rollback target : " + c1);

		Result rollback = run(Map.of(), "rollback");

		assertThat(rollback.exit()).as(rollback.output()).isZero();
		assertThat(rollback.output()).contains("rolled back: " + c1);
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c1);
		assertThat(head()).isEqualTo(c1);
		assertThat(deployState("current")).isEqualTo(c1);

		Result again = run(Map.of(), "rollback");
		assertThat(again.exit()).isEqualTo(1);
		assertThat(again.output()).contains("nothing to go back to");
	}

	@Test
	@DisplayName("manual rollback across a database migration is refused unless --force")
	void rollbackAcrossMigrationsNeedsForce() throws Exception {
		assertThat(deploy(c2).exit()).isZero();
		assertThat(deploy(c3).exit()).isZero();

		Result refused = run(Map.of(), "rollback");

		assertThat(refused.exit()).isEqualTo(1);
		assertThat(refused.output()).contains("Flyway only goes forward").contains("--force");
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c3);
		assertThat(head()).isEqualTo(c3);

		Result forced = run(Map.of(), "rollback", "--force");

		assertThat(forced.exit()).as(forced.output()).isZero();
		assertThat(image("current")).isEqualTo("img-controlweb-app:" + c2);
		assertThat(head()).isEqualTo(c2);
	}
}
