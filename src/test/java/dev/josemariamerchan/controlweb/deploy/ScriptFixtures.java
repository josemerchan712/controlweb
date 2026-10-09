package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The ONE way the tests of the shell scripts create the stand-ins ("fakes") of docker, iptables, age, systemctl... that the scripts run.
 *
 * Why it exists: on Windows nothing has an execute bit, so a fake that was never made executable still "works" there, while on Linux (CI,
 * and the server) the shell answers "Permission denied" / exit 126. Git also stores the files of src/test/resources with mode 100644. Every
 * fake therefore goes through here, which (1) copies/writes it into the test's own temporary folder, (2) normalises the line endings to LF
 * (a CR after the shebang makes Linux look for the interpreter "bash\r"), (3) makes it executable, and (4) CHECKS that it is: it fails the
 * test at once, with a clear message, instead of letting the script under test fail somewhere else. ScriptTestsGuardianTest forbids
 * creating fakes any other way.
 */
final class ScriptFixtures {

	private ScriptFixtures() {
	}

	/** Makes {@code file} executable for everybody and proves it. */
	static Path executable(Path file) throws IOException {
		assertThat(file).as("the fake script exists").isRegularFile();
		boolean changed = file.toFile().setExecutable(true, false);
		assertThat(changed || Files.isExecutable(file)).as("could not make %s executable", file).isTrue();
		assertThat(Files.isExecutable(file)).as("%s must be executable (on Linux a fake without the x bit gives 'Permission denied'/exit 126)", file).isTrue();
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
			assertThat(PosixFilePermissions.toString(permissions)).as("mode of %s", file).startsWith("rwx");
		}
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertThat(text).as("%s starts with a shebang", file).startsWith("#!");
		assertThat(text.lines().findFirst().orElse("")).as("first line of %s has no carriage return", file).doesNotContain("\r");
		assertThat(text).as("%s has no CRLF line endings", file).doesNotContain("\r\n");
		return file;
	}

	/** Writes a fake script (LF line endings) and makes it executable. */
	static Path write(Path file, String content) throws IOException {
		Files.writeString(file, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
		return executable(file);
	}

	/** Copies a fake from the classpath (e.g. "/fakedocker/backup-docker.sh") into {@code directory} under {@code name}, executable. */
	static Path copyResource(String resource, Path directory, String name) throws IOException {
		try (InputStream in = ScriptFixtures.class.getResourceAsStream(resource)) {
			assertThat(in).as("test resource %s", resource).isNotNull();
			Files.createDirectories(directory);
			return write(directory.resolve(name), new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	/** Copies a fake from the source tree (e.g. src/test/resources/fakedocker/fake-iptables.sh) into {@code directory}, executable. */
	static Path copyFile(Path source, Path directory, String name) throws IOException {
		Files.createDirectories(directory);
		return write(directory.resolve(name), Files.readString(source, StandardCharsets.UTF_8));
	}

	/** A path as bash wants it, also on Windows. */
	static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}
}
