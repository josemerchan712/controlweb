package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/backup/serve-backup.sh: the read-only script that the restricted SSH key can run as root. The request is one line on
 * standard input; it answers "list", "sha256 NAME" and "get NAME" and refuses everything else, above all anything that is not the
 * exact name of a stored copy (no path, no "..", no glob, no option, no second line).
 */
class ServeBackupScriptTest {

	private static final String BASH = bash();
	private static final String A = "controlweb-20260101T041500Z.dump.age";
	private static final String B = "controlweb-20260102T041500Z.dump.age";

	@TempDir
	Path temp;

	private Path backups;
	private byte[] binary;

	private record Result(int exit, byte[] out, String error) {
		String text() {
			return new String(out, StandardCharsets.ISO_8859_1);
		}
	}

	private static String bash() {
		for (String candidate : List.of("C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash")) {
			if (Files.exists(Path.of(candidate))) {
				return candidate;
			}
		}
		return null;
	}

	@BeforeEach
	void world() throws IOException {
		assumeTrue(BASH != null, "bash is not available");
		backups = Files.createDirectories(temp.resolve("backups"));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes("age-encryption.org/v1".getBytes(StandardCharsets.US_ASCII));
		for (int round = 0; round < 40; round++) {
			for (int value = 0; value < 256; value++) {
				bytes.write(value);
			}
		}
		binary = bytes.toByteArray();
		Files.write(backups.resolve(A), binary);
		Files.write(backups.resolve(B), "age-encryption.org/v1 second".getBytes(StandardCharsets.US_ASCII));
		Files.writeString(backups.resolve(".controlweb-20260103T041500Z.dump.age.partial"), "partial");
		Files.writeString(backups.resolve("secret.txt"), "TOP SECRET");
	}

	private Result serve(byte[] stdin) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(BASH, "deploy/backup/serve-backup.sh");
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().put("BACKUP_DIR", backups.toAbsolutePath().toString().replace('\\', '/'));
		Path errors = temp.resolve("stderr.txt");
		builder.redirectError(errors.toFile());
		Process process = builder.start();
		try {
			process.getOutputStream().write(stdin);
			process.getOutputStream().close();
		} catch (IOException closedEarly) {
			// the script reads at most 200 bytes and may exit before a huge request is fully written: that is the point of the test
		}
		byte[] out = process.getInputStream().readAllBytes();
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), out, Files.readString(errors));
	}

	private Result serve(String request) throws Exception {
		return serve((request + "\n").getBytes(StandardCharsets.ISO_8859_1));
	}

	@Test
	@DisplayName("list: the stored copies, newest first, with their sizes; partial files and other files are not listed")
	void listsOnlyCopies() throws Exception {
		Result result = serve("list");

		assertThat(result.exit()).as(result.error()).isZero();
		assertThat(result.text().strip().split("\\R")).containsExactly(B + " 28", A + " " + binary.length);
	}

	@Test
	@DisplayName("get: returns the file byte for byte (every byte value, CR, LF and Ctrl-Z included) and nothing else")
	void getIsByteExact() throws Exception {
		Result result = serve("get " + A);

		assertThat(result.exit()).as(result.error()).isZero();
		assertThat(result.out()).isEqualTo(binary);
	}

	@Test
	@DisplayName("sha256: the checksum of that file, in sha256sum format")
	void checksum() throws Exception {
		Result result = serve("sha256 " + A);

		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
		assertThat(result.exit()).as(result.error()).isZero();
		// sha256sum writes "hash  name" on Linux and "hash *name" in Git for Windows; the PC script only reads the first word
		assertThat(result.text().strip()).matches(expected + "( {2}| [*])" + A.replace(".", "[.]"));
	}

	@Test
	@DisplayName("a CRLF line ending in the request (a request file written on Windows) is accepted")
	void acceptsCrLf() throws Exception {
		Result result = serve(("get " + B + "\r\n").getBytes(StandardCharsets.ISO_8859_1));

		assertThat(result.exit()).as(result.error()).isZero();
		assertThat(result.text()).isEqualTo("age-encryption.org/v1 second");
	}

	@Test
	@DisplayName("everything that is not an exact copy name is refused with exit 2 and NO output: paths, .., globs, options, partial files, other files")
	void refusesAnythingElse() throws Exception {
		List<String> bad = List.of("get ../secret.txt", "get secret.txt", "get /etc/passwd", "get " + backups.resolve("secret.txt").toString().replace('\\', '/'),
				"get controlweb-*.dump.age", "get controlweb-20260101T041500Z.dump.age ", "get " + A + " " + B, "get -r " + A,
				"get .controlweb-20260103T041500Z.dump.age.partial", "get controlweb-20260103T041500Z.dump.age", "get CONTROLWEB-20260101T041500Z.DUMP.AGE",
				"get ", "get", "sha256", "sha256 secret.txt", "sha256 ../" + A, "list now", "list -l", "LIST", "delete " + A, "put " + A, "rm " + A, "sh", "bash -c id",
				"get " + A + ";id", "get $(id)", "get `id`", "get " + A + "|cat", "get " + A + "&id", "get " + A + ">x", "get " + A + "\\x", "get \"" + A + "\"",
				"get '" + A + "'", "", " ", "get\t" + A);
		for (String request : bad) {
			Result result = serve(request);
			assertThat(result.exit()).as("request '%s' -> %s", request, result.text()).isEqualTo(2);
			assertThat(result.out()).as("request '%s' must print nothing", request).isEmpty();
			assertThat(result.error()).as("request '%s'", request).contains("refused");
		}
	}

	@Test
	@DisplayName("a second line is ignored (only the first request is served) and a huge request is cut, not executed")
	void onlyTheFirstLine() throws Exception {
		Result two = serve(("list\nget " + A + "\n").getBytes(StandardCharsets.ISO_8859_1));
		Result huge = serve("get " + "a".repeat(5000));

		assertThat(two.exit()).isZero();
		assertThat(two.text()).doesNotContain("age-encryption.org/v1").contains(B);
		assertThat(huge.exit()).isEqualTo(2);
		assertThat(huge.out()).isEmpty();
	}

	@Test
	@DisplayName("a symbolic link with a valid name is not served (it could point anywhere)")
	void symlinkIsNotServed() throws Exception {
		assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("windows"), "symbolic links need privileges on Windows");
		Files.createSymbolicLink(backups.resolve("controlweb-20260105T041500Z.dump.age"), backups.resolve("secret.txt"));

		Result result = serve("get controlweb-20260105T041500Z.dump.age");

		assertThat(result.exit()).isEqualTo(2);
		assertThat(result.text()).doesNotContain("SECRET");
	}

	@Test
	@DisplayName("the script writes and deletes nothing: the folder is identical after every kind of request")
	void readOnly() throws Exception {
		List<String> before = names();
		for (String request : List.of("list", "get " + A, "sha256 " + A, "get ../x", "rm " + A)) {
			serve(request);
		}

		assertThat(names()).isEqualTo(before);
		assertThat(Files.readAllBytes(backups.resolve(A))).isEqualTo(binary);
		String source = Files.readString(Path.of("deploy/backup/serve-backup.sh"));
		assertThat(source).doesNotContain("rm ").doesNotContain("mv ").doesNotContain(" > ").doesNotContain("chmod").doesNotContain("eval").doesNotContain("$request ");
	}

	private List<String> names() throws IOException {
		try (var stream = Files.list(backups)) {
			return stream.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
