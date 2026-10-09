package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs deploy/verify-container.sh and deploy/verify-image.sh against a FAKE docker (a script that answers like Docker
 * would), so the checks are tested without Docker. Its main job: the way Docker (or Windows) formats a value -- a trailing
 * space, blank lines, CRLF, a different order of names -- must never decide whether a check passes. A real run in CI once
 * failed only because a list of network names started with a space.
 */
@Tag("slow-scripts")
class VerifyScriptsTest {

	private static final String BASH = bashExecutable();

	@TempDir
	Path temp;

	@BeforeAll
	static void bashIsNeeded() {
		assumeTrue(BASH != null, "bash is not available");
	}

	private static String bashExecutable() {
		for (String candidate : List.of("C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash")) {
			if (Files.exists(Path.of(candidate))) {
				return candidate;
			}
		}
		return null;
	}

	private record Result(int exit, String output) {
	}

	private Path fakeDocker(String resource) throws IOException {
		Path directory = Files.createDirectories(temp.resolve("bin-" + resource));
		ScriptFixtures.copyResource("/fakedocker/" + resource + ".sh", directory, "docker");
		return directory;
	}

	private Result run(Path fakeDockerDirectory, Map<String, String> fakeEnvironment, String script, String... arguments)
			throws Exception {
		List<String> command = new java.util.ArrayList<>(List.of(BASH, script));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		String path = System.getenv("PATH") == null ? "" : System.getenv("PATH");
		builder.environment().put("PATH", fakeDockerDirectory + java.io.File.pathSeparator + path);
		builder.environment().putAll(fakeEnvironment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script finished").isTrue();
		return new Result(process.exitValue(), output);
	}

	// ---- verify-container.sh ----

	private Result container(Map<String, String> fakeEnvironment) throws Exception {
		return run(fakeDocker("container"), fakeEnvironment, "deploy/verify-container.sh", "controlweb-app-1");
	}

	@Test
	@DisplayName("verify-container.sh: a perfectly configured container passes")
	void containerPasses() throws Exception {
		Result result = container(Map.of());

		assertThat(result.output()).contains("container verified");
		assertThat(result.exit()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"controlweb_proxy controlweb_db controlweb_egress ", // exactly what the real template prints: this broke CI
			"\n\ncontrolweb_egress controlweb_db\n\tcontrolweb_proxy \r\n" })
	@DisplayName("verify-container.sh: the format and order of the network names never matter")
	void networkNamesAreFormattingProof(String networks) throws Exception {
		Result result = container(Map.of("FAKE_NETWORKS", networks));

		assertThat(result.output()).contains("ok:   networks = controlweb_db controlweb_egress controlweb_proxy");
		assertThat(result.exit()).as(result.output()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "crlf", "spaces" })
	@DisplayName("verify-container.sh: CRLF line endings, padding spaces or blank lines around EVERY value change nothing")
	void noiseAroundValuesIsIgnored(String noise) throws Exception {
		Result result = container(Map.of("FAKE_NOISE", noise));

		assertThat(result.output()).doesNotContain("FAIL");
		assertThat(result.exit()).as(result.output()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "controlweb_proxy controlweb_db controlweb_egress rottenodds_edge" })
	@DisplayName("verify-container.sh: a missing, extra or duplicated network DOES fail")
	void wrongNetworksFail(String networks) throws Exception {
		Result result = container(Map.of("FAKE_NETWORKS", networks));

		assertThat(result.output()).contains("FAIL: networks");
		assertThat(result.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("verify-container.sh: a writable root filesystem fails, however it is formatted")
	void writableRootFails() throws Exception {
		Result result = container(Map.of("FAKE_READONLY", "false", "FAKE_NOISE", "crlf"));

		assertThat(result.output()).contains("FAIL: read-only root filesystem");
		assertThat(result.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("verify-container.sh: running as another user fails")
	void wrongUserFails() throws Exception {
		Result result = container(Map.of("FAKE_USER", "0:0"));

		assertThat(result.output()).contains("FAIL: user");
		assertThat(result.exit()).isEqualTo(1);
	}

	// ---- verify-image.sh ----

	private Path tar(String... paths) throws IOException {
		Path tar = temp.resolve("image-" + paths.length + "-" + System.nanoTime() + ".tar");
		try (OutputStream out = Files.newOutputStream(tar); TarArchiveOutputStream archive = new TarArchiveOutputStream(out)) {
			archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			for (String path : List.of("app/", "app/application.jar", "app/lib/", "app/lib/spring-core.jar", "usr/", "usr/bin/",
					"usr/bin/wget", "etc/", "etc/passwd", "opt/java/openjdk/", "opt/java/openjdk/legal/")) {
				archive.putArchiveEntry(new TarArchiveEntry(path));
				archive.closeArchiveEntry();
			}
			for (String path : paths) {
				archive.putArchiveEntry(new TarArchiveEntry(path));
				archive.closeArchiveEntry();
			}
		}
		return tar;
	}

	private Path jar(String... extraEntries) throws IOException {
		Path jar = temp.resolve("application-" + System.nanoTime() + ".jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (String entry : List.of("META-INF/MANIFEST.MF", "dev/josemariamerchan/controlweb/ControlwebApplication.class",
					"application.yml", "application-prod.yml", "db/migration/V1__init.sql")) {
				out.putNextEntry(new JarEntry(entry));
				out.closeEntry();
			}
			for (String entry : extraEntries) {
				out.putNextEntry(new JarEntry(entry));
				out.closeEntry();
			}
		}
		return jar;
	}

	private Result image(Path tar, Path jar, Map<String, String> extra) throws Exception {
		Map<String, String> environment = new HashMap<>(extra);
		environment.put("FAKE_TAR", tar.toAbsolutePath().toString());
		environment.put("FAKE_JAR", jar.toAbsolutePath().toString());
		return run(fakeDocker("image"), environment, "deploy/verify-image.sh", "controlweb-app:test");
	}

	@Test
	@DisplayName("verify-image.sh: a clean image passes")
	void cleanImagePasses() throws Exception {
		Result result = image(tar(), jar(), Map.of());

		assertThat(result.output()).contains("image verified");
		assertThat(result.exit()).as(result.output()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "crlf", "spaces" })
	@DisplayName("verify-image.sh: CRLF, padding spaces or blank lines around the docker answers change nothing")
	void noiseIsIgnored(String noise) throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_NOISE", noise));

		assertThat(result.output()).doesNotContain("FAIL");
		assertThat(result.exit()).as(result.output()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "app/.env", "app/src/main/java/Foo.java", "app/docs/DESPLIEGUE.md" })
	@DisplayName("verify-image.sh: source, tests, .env, .git, docs or build files inside the image make it fail")
	void forbiddenFilesFail(String file) throws Exception {
		Result result = image(tar(file), jar(), Map.of());

		assertThat(result.output()).contains("FAIL: files that must not be in the image");
		assertThat(result.exit()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "dev/josemariamerchan/controlweb/SiteServiceTest.class" })
	@DisplayName("verify-image.sh: a jar with test classes or sources makes it fail")
	void jarWithTestsFails(String entry) throws Exception {
		Result result = image(tar(), jar(entry), Map.of());

		assertThat(result.output()).contains("FAIL: the application jar contains");
		assertThat(result.exit()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "" })
	@DisplayName("verify-image.sh: an image that runs as root (or as nobody in particular) fails")
	void rootUserFails(String user) throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_USER", user));

		assertThat(result.output()).contains("FAIL: the image runs as root");
		assertThat(result.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("verify-image.sh: root is still detected when docker pads the answer with spaces (a padded '0' must not look like a user)")
	void paddedRootUserStillFails() throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_USER", "0", "FAKE_NOISE", "spaces"));

		assertThat(result.output()).contains("FAIL: the image runs as root");
		assertThat(result.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("verify-image.sh: no HEALTHCHECK fails")
	void missingHealthcheckFails() throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_HEALTH", ""));

		assertThat(result.output()).contains("FAIL: no HEALTHCHECK");
		assertThat(result.exit()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "JWT_SECRET=abc" })
	@DisplayName("verify-image.sh: a secret-looking environment variable baked into the image fails")
	void secretEnvFails(String variable) throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_ENV", "SPRING_PROFILES_ACTIVE=prod\n" + variable));

		assertThat(result.output()).contains("FAIL: an environment variable of the image looks like a secret");
		assertThat(result.exit()).isEqualTo(1);
	}

	@Test
	@DisplayName("verify-image.sh: a default profile other than prod fails")
	void wrongProfileFails() throws Exception {
		Result result = image(tar(), jar(), Map.of("FAKE_ENV", "SPRING_PROFILES_ACTIVE=dev\nLANG=C.UTF-8"));

		assertThat(result.output()).contains("FAIL: default profile is not prod");
		assertThat(result.exit()).isEqualTo(1);
	}
}
