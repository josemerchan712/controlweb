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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/caddy/caddy-install.sh with a fake docker (src/test/resources/fakedocker/caddy-docker.sh). The point of the script is that
 * the Caddy of Rotten Odds can never be left with a broken configuration on disk: the fragment is checked, validated by a disposable
 * container of the same image BEFORE it touches the sites folder, moved atomically, reloaded gracefully, and undone if the reload
 * fails. Every failure path must leave the folder byte-identical to what it was.
 */
class CaddyInstallScriptTest {

	private static final String BASH = bash();
	private static final String IMAGE = "sha256:" + "ab".repeat(32);
	private static final Path SCRIPT = Path.of("deploy/caddy/caddy-install.sh");
	private static final String OLD = "status-api.josemariamerchan.dev {\n\treverse_proxy controlweb-app:8080\n\trespond /old 200\n}\n";
	private static final String ROTTEN = "rottenodds-api.josemariamerchan.dev {\n\treverse_proxy api:8080\n}\n";

	@TempDir
	Path temp;

	private Path sites;
	private Path state;
	private Path caddyfile;
	private Path fragment;
	private Path tmp;
	private Path fakeDocker;

	private record Result(int exit, String output) {
	}

	private static String bash() {
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

	@BeforeEach
	void world() throws IOException {
		assumeTrue(BASH != null, "bash is not available");
		sites = Files.createDirectories(temp.resolve("caddy-sites"));
		state = Files.createDirectories(temp.resolve("state"));
		tmp = Files.createDirectories(temp.resolve("tmp"));
		fakeDocker = ScriptFixtures.copyFile(Path.of("src/test/resources/fakedocker/caddy-docker.sh"), temp.resolve("bin"), "docker");
		caddyfile = temp.resolve("Caddyfile");
		Files.writeString(caddyfile, "{\n\temail ops@example.com\n}\n\nrottenodds-api.josemariamerchan.dev {\n\treverse_proxy api:8080\n}\n\n# other projects\nimport /etc/caddy/sites/*.caddy\n");
		Files.writeString(sites.resolve("rottenodds-extra.caddy"), ROTTEN);
		fragment = temp.resolve("candidate.caddy");
		Files.copy(Path.of("deploy/caddy/controlweb.caddy"), fragment);
	}

	private Result install(Map<String, String> extra, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of(BASH, SCRIPT.toString()));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		Map<String, String> env = builder.environment();
		env.put("ALLOW_NON_ROOT", "1");
		env.put("SITES_DIR", slashes(sites));
		env.put("DOCKER", slashes(fakeDocker));
		env.put("LOCK_DIR", slashes(temp.resolve("lock")));
		env.put("TMPDIR", slashes(tmp));
		env.put("FAKE_STATE", slashes(state));
		env.put("FAKE_CADDY_NAMES", "rottenodds-caddy-1");
		env.put("FAKE_IMAGE", IMAGE);
		env.put("FAKE_CADDYFILE", slashes(caddyfile));
		env.put("FAKE_SITES_MOUNT", slashes(sites));
		env.put("FAKE_SITES_REAL", slashes(sites));
		env.putAll(extra);
		Process process = builder.start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), output);
	}

	private Result installCandidate(Map<String, String> extra) throws Exception {
		return install(extra, slashes(fragment));
	}

	private Map<String, String> folder() throws IOException {
		Map<String, String> content = new TreeMap<>();
		try (Stream<Path> files = Files.list(sites)) {
			for (Path file : files.toList()) {
				content.put(file.getFileName().toString(), Files.readString(file));
			}
		}
		return content;
	}

	private String dockerLog() throws IOException {
		return Files.exists(state.resolve("docker.log")) ? Files.readString(state.resolve("docker.log")) : "";
	}

	private int reloads() throws IOException {
		return Files.exists(state.resolve("reloads")) ? Integer.parseInt(Files.readString(state.resolve("reloads")).strip()) : 0;
	}

	private void assertNoLeftovers() throws IOException {
		try (Stream<Path> files = Files.list(tmp)) {
			assertThat(files.toList()).as("the temporary working folder is removed").isEmpty();
		}
		assertThat(temp.resolve("lock")).as("the lock is released").doesNotExist();
	}

	// ---- the good path ----------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("first install: validated by a disposable container of the SAME image id, then moved into place, then reloaded; other projects' fragments untouched")
	void firstInstall() throws Exception {
		String expected = Files.readString(fragment);

		Result result = installCandidate(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		Map<String, String> after = folder();
		assertThat(after).containsEntry("controlweb.caddy", expected).containsEntry("rottenodds-extra.caddy", ROTTEN);
		assertThat(after.keySet()).as("no temporary or backup file when there was nothing before").containsExactlyInAnyOrder("controlweb.caddy", "rottenodds-extra.caddy");

		List<String> calls = dockerLog().lines().toList();
		assertThat(calls.get(0)).startsWith("ps --filter label=com.docker.compose.project=rottenodds --filter label=com.docker.compose.service=caddy --filter status=running");
		String run = calls.stream().filter(line -> line.startsWith("run ")).findFirst().orElseThrow();
		assertThat(run).contains("--rm").contains("--network none").contains("--read-only").contains("--cap-drop ALL").contains("--security-opt no-new-privileges")
				.contains("--memory 256m").contains(" " + IMAGE + " caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile").contains(slashes(caddyfile) + ":/etc/caddy/Caddyfile:ro")
				.contains(":/etc/caddy/sites:ro").doesNotContain(" -p ").doesNotContain("--network host").doesNotContain("latest");
		assertThat(run).as("the validated folder is a COPY, not the real sites folder").doesNotContain(slashes(sites) + ":");
		assertThat(calls.get(calls.size() - 1)).isEqualTo("exec rottenodds-caddy-1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile");
		assertThat(calls.indexOf(run)).as("validation comes before the reload").isLessThan(calls.size() - 1);
		assertThat(Files.readString(state.resolve("validated.txt"))).contains("controlweb.caddy").contains("rottenodds-extra.caddy");
		assertThat(Files.readString(state.resolve("validated-content.txt"))).contains("reverse_proxy controlweb-app:8080").contains("rottenodds-api");
		assertThat(Files.readString(state.resolve("reload-saw-fragment.txt"))).as("the new fragment was already in place when Caddy reloaded").isEqualTo(expected);
		assertThat(Files.readString(state.resolve("reload-saw.txt"))).as("no temporary file the import glob could pick up").doesNotContain("controlweb.caddy.new").doesNotContain(".partial");
		assertThat(result.output()).contains("caddy reloaded");
		assertThat(dockerLog()).as("the environment of Caddy is never logged").doesNotContain("do-not-log-me");
		assertThat(result.output()).doesNotContain("do-not-log-me");
		assertNoLeftovers();
	}

	@Test
	@DisplayName("update: the previous fragment is kept (hidden, so the import glob ignores it) and the new one is in place")
	void update() throws Exception {
		Files.writeString(sites.resolve("controlweb.caddy"), OLD);

		Result result = installCandidate(Map.of());

		assertThat(result.exit()).as(result.output()).isZero();
		Map<String, String> after = folder();
		assertThat(after.get("controlweb.caddy")).isEqualTo(Files.readString(fragment));
		assertThat(after.get(".controlweb.caddy.previous")).isEqualTo(OLD);
		assertThat(after.keySet()).noneMatch(name -> name.endsWith(".caddy") && name.startsWith(".")).containsOnly("controlweb.caddy", ".controlweb.caddy.previous", "rottenodds-extra.caddy");
		assertNoLeftovers();
	}

	@Test
	@DisplayName("--check validates and changes nothing at all (no move, no reload)")
	void checkOnly() throws Exception {
		Files.writeString(sites.resolve("controlweb.caddy"), OLD);
		Map<String, String> before = folder();

		Result result = install(Map.of(), "--check", slashes(fragment));

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(folder()).isEqualTo(before);
		assertThat(reloads()).isZero();
		assertThat(dockerLog()).contains("run ").doesNotContain("exec ");
		assertThat(result.output()).contains("nothing was installed");
	}

	// ---- failure paths: the folder stays exactly as it was -----------------------------------------------------------------------

	@Test
	@DisplayName("Caddy rejects the configuration: exit 1, the folder is byte-identical (old fragment kept), nothing moved, no reload")
	void validationFailure() throws Exception {
		Files.writeString(sites.resolve("controlweb.caddy"), OLD);
		Files.writeString(fragment, Files.readString(fragment).replace("encode zstd gzip", "encode zstd gzip BROKEN"));
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of());

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("Caddy rejected the configuration").contains("NOTHING was changed");
		assertThat(folder()).isEqualTo(before);
		assertThat(reloads()).isZero();
		assertThat(dockerLog()).doesNotContain("exec ");
		assertNoLeftovers();
	}

	@Test
	@DisplayName("the validation container itself failing (timeout, Docker error) also changes nothing")
	void validatorCannotRun() throws Exception {
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of("FAKE_VALIDATE_FAIL", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(folder()).isEqualTo(before);
		assertThat(reloads()).isZero();
	}

	@Test
	@DisplayName("reload fails after the move: the previous fragment is put back byte for byte, a second reload makes Caddy match the folder again, exit 1")
	void reloadFailureWithPrevious() throws Exception {
		Files.writeString(sites.resolve("controlweb.caddy"), OLD);
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of("FAKE_RELOAD_FAIL", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("the previous fragment was put back").contains("back to what it was before");
		assertThat(folder()).isEqualTo(before);
		assertThat(reloads()).as("the failed reload and one more to converge").isEqualTo(2);
		assertNoLeftovers();
	}

	@Test
	@DisplayName("reload fails on a FIRST install: the new fragment is removed again, the folder is as before")
	void reloadFailureWithoutPrevious() throws Exception {
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of("FAKE_RELOAD_FAIL", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("the new fragment was removed");
		assertThat(folder()).isEqualTo(before);
		assertNoLeftovers();
	}

	@Test
	@DisplayName("a reload that fails only the first time (timeout that was applied anyway) still ends with the folder as before and Caddy reloaded from it")
	void reloadFailsOnceThenConverges() throws Exception {
		Files.writeString(sites.resolve("controlweb.caddy"), OLD);
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of("FAKE_RELOAD_FAIL_FIRST", "1"));

		assertThat(result.exit()).isEqualTo(1);
		assertThat(folder()).isEqualTo(before);
		assertThat(reloads()).isEqualTo(2);
		assertThat(Files.readString(state.resolve("reload-saw-fragment.txt"))).as("the last reload saw the restored old fragment").isEqualTo(OLD);
	}

	@Test
	@DisplayName("the running Caddy cannot be identified (none, or two), has an unusable image id, no Caddyfile on the host, no sites mount, or no import line: refused before anything is run or changed")
	void environmentChecks() throws Exception {
		Map<String, String> before = folder();
		Path withoutImport = temp.resolve("Caddyfile.noimport");
		Files.writeString(withoutImport, "rottenodds-api.josemariamerchan.dev {\n\treverse_proxy api:8080\n}\n# import /etc/caddy/sites/*.caddy\n");
		Path otherSites = Files.createDirectories(temp.resolve("another-folder"));
		List<Map<String, String>> cases = List.of(Map.of("FAKE_CADDY_NAMES", ""), Map.of("FAKE_CADDY_NAMES", "rottenodds-caddy-1\nsomething-else-caddy-1"),
				Map.of("FAKE_IMAGE", "caddy:2-alpine"), Map.of("FAKE_IMAGE", "sha256:abc"), Map.of("FAKE_CADDYFILE", slashes(temp.resolve("missing"))), Map.of("FAKE_CADDYFILE", ""),
				Map.of("FAKE_SITES_MOUNT", ""), Map.of("FAKE_SITES_MOUNT", slashes(otherSites)), Map.of("FAKE_CADDYFILE", slashes(withoutImport)));
		for (Map<String, String> environment : cases) {
			Files.deleteIfExists(state.resolve("docker.log"));
			Result result = installCandidate(environment);
			assertThat(result.exit()).as(environment + " -> " + result.output()).isEqualTo(1);
			assertThat(folder()).as(environment.toString()).isEqualTo(before);
			assertThat(dockerLog()).as(environment.toString()).doesNotContain("run ").doesNotContain("exec ");
		}
		assertThat(installCandidate(Map.of("FAKE_CADDYFILE", slashes(withoutImport))).output()).contains("no 'import /etc/caddy/sites/*.caddy'");
		assertNoLeftovers();
	}

	@Test
	@DisplayName("an explicit CADDY_CONTAINER skips the search by labels")
	void explicitContainer() throws Exception {
		Result result = installCandidate(Map.of("CADDY_CONTAINER", "my-caddy", "FAKE_CADDY_NAMES", ""));

		assertThat(result.exit()).as(result.output()).isZero();
		assertThat(dockerLog()).doesNotContain("ps ").contains("exec my-caddy caddy reload");
	}

	@Test
	@DisplayName("a lock left by another run stops it before anything happens")
	void lock() throws Exception {
		Files.createDirectories(temp.resolve("lock"));
		Map<String, String> before = folder();

		Result result = installCandidate(Map.of());

		assertThat(result.exit()).isEqualTo(1);
		assertThat(result.output()).contains("another caddy-install.sh is running");
		assertThat(folder()).isEqualTo(before);
		assertThat(temp.resolve("lock")).as("the other run's lock is not ours to remove").exists();
	}

	// ---- what the fragment may contain ----------------------------------------------------------------------------------------------

	@Test
	@DisplayName("fragments that would define another site, global options, imports, files, environment access, another upstream, or are empty/huge/binary are refused BEFORE Docker is asked anything")
	void fragmentRules() throws Exception {
		Map<String, String> before = folder();
		String good = Files.readString(fragment);
		List<String> bad = List.of(
				good.replace("status-api.josemariamerchan.dev {", "rottenodds-api.josemariamerchan.dev {"),
				good.replace("status-api.josemariamerchan.dev {", "status-api.josemariamerchan.dev, rottenodds-api.josemariamerchan.dev {"),
				good.replace("status-api.josemariamerchan.dev {", "*.josemariamerchan.dev {"),
				good + "\nother.example.com {\n\trespond 200\n}\n",
				"{\n\tadmin off\n}\n" + good,
				"{\n\temail me@example.com\n}\n" + good,
				good.replace("\tencode zstd gzip", "\timport /etc/passwd"),
				good.replace("\tencode zstd gzip", "\texec rm -rf /"),
				good.replace("\tencode zstd gzip", "\tfile_server"),
				good.replace("\tencode zstd gzip", "\troot * /"),
				good.replace("\tencode zstd gzip", "\ttls internal"),
				good.replace("\tencode zstd gzip", "\tlog"),
				good.replace("\tencode zstd gzip", "\thandle_path /x {\n\t}"),
				good.replace("-Server", "X-Secret {$DB_PASSWORD}"),
				good.replace("-Server", "X-Secret {env.DB_PASSWORD}"),
				good.replace("-Server", "X-Secret {file./etc/shadow}"),
				good.replace("reverse_proxy controlweb-app:8080", "reverse_proxy 172.18.0.1:5432"),
				good.replace("reverse_proxy controlweb-app:8080", "reverse_proxy controlweb-app:8080 api:8080"),
				good.replace("reverse_proxy controlweb-app:8080", "reverse_proxy controlweb-app:8080 {\n\t\tto evil:80\n\t}"),
				good + "\treverse_proxy controlweb-app:8080\n",
				good.replace("\treverse_proxy controlweb-app:8080\n", ""),
				"",
				good + "#" + "x".repeat(9000),
				good.replace("encode", "enc\u0000ode"));
		int index = 0;
		for (String content : bad) {
			Files.write(fragment, content.getBytes(StandardCharsets.UTF_8));
			Files.deleteIfExists(state.resolve("docker.log"));
			Result result = installCandidate(Map.of());
			assertThat(result.exit()).as("case %d -> %s", index, result.output()).isEqualTo(1);
			assertThat(dockerLog()).as("case %d: Docker must not even be asked", index).isEmpty();
			assertThat(folder()).as("case %d", index).isEqualTo(before);
			index++;
		}
	}

	@Test
	@DisplayName("a missing fragment, an unknown option, or two fragments are refused")
	void arguments() throws Exception {
		assertThat(install(Map.of(), slashes(temp.resolve("nope.caddy"))).exit()).isEqualTo(1);
		assertThat(install(Map.of(), "--force", slashes(fragment)).output()).contains("unknown option");
		assertThat(install(Map.of(), slashes(fragment), slashes(fragment)).output()).contains("only one fragment");
		assertThat(dockerLog()).isEmpty();
	}

	@Test
	@DisplayName("the fragment shipped in the repository passes every rule and the validation (it is what S6 installs)")
	void shippedFragment() throws Exception {
		Result result = install(Map.of(), "--check");

		assertThat(result.exit()).as(result.output()).isZero();
		String text = Files.readString(Path.of("deploy/caddy/controlweb.caddy"));
		assertThat(text).startsWith("status-api.josemariamerchan.dev {").contains("reverse_proxy controlweb-app:8080").contains("respond @hidden 404")
				.contains("path /actuator* /v3/api-docs* /swagger-ui*").contains("not path /actuator/health").contains("Strict-Transport-Security")
				.contains("Content-Security-Policy \"default-src 'none'; frame-ancestors 'none'\"").contains("max_size 64KB").contains("-Server");
		assertThat(text).doesNotContain("import").doesNotContain("Access-Control");
	}

	@Test
	@DisplayName("the move is an atomic rename of a hidden temporary name in the SAME folder, and nothing but this script's own files is ever deleted")
	void atomicityInTheCode() throws IOException {
		String code = Files.readAllLines(SCRIPT).stream().filter(line -> !line.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));

		assertThat(code).contains("incoming=\"$SITES_DIR/.$FRAGMENT_NAME.new\"").contains("mv -f -- \"$incoming\" \"$final\"").contains("previous=\"$SITES_DIR/.$FRAGMENT_NAME.previous\"");
		assertThat(code).doesNotContain("rm -rf \"$SITES_DIR").doesNotContain("rm -f \"$SITES_DIR/*").doesNotContain("cp -- \"$fragment\" \"$final\"").doesNotContain("> \"$final\"");
		assertThat(code.indexOf("\"$DOCKER\" run")).isLessThan(code.indexOf("mv -f -- \"$incoming\" \"$final\""));
		assertThat(code.indexOf("mv -f -- \"$incoming\" \"$final\"")).isLessThan(code.indexOf("caddy reload"));
	}
}
