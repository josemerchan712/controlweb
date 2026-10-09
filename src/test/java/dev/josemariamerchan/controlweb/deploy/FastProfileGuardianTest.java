package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opt-in Maven profile "fast" may only skip the slow script tests when somebody asks for it: it must never be active by default,
 * and the tests it hides must be exactly the three slow script classes (so nothing else disappears silently).
 */
class FastProfileGuardianTest {

	private static final List<String> SLOW = List.of("BackupScriptsTest", "DeployScriptTest", "VerifyScriptsTest");

	@Test
	@DisplayName("pom: surefire excludes nothing by default; only profile 'fast' (never active by default) excludes slow-scripts")
	void profileIsOptIn() throws IOException {
		String pom = Files.readString(Path.of("pom.xml"));

		assertThat(pom).contains("<test.excluded.groups></test.excluded.groups>").contains("<excludedGroups>${test.excluded.groups}</excludedGroups>");
		assertThat(pom).doesNotContain("activeByDefault").doesNotContain("<activation>");
		assertThat(pom.split("<test\\.excluded\\.groups>", -1)).as("default (empty) + the one in profile fast").hasSize(3);
		assertThat(pom).containsPattern("(?s)<profile>\\s*<id>fast</id>\\s*<properties>\\s*<test\\.excluded\\.groups>slow-scripts</test\\.excluded\\.groups>");
		assertThat(pom).doesNotContain("<skipTests>").doesNotContain("<skip>true</skip>");
	}

	@Test
	@DisplayName("exactly BackupScriptsTest, DeployScriptTest and VerifyScriptsTest are tagged slow-scripts, and no other test class is")
	void onlyTheSlowClassesAreTagged() throws IOException {
		try (var files = Files.walk(Path.of("src/test/java"))) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String name = file.getFileName().toString().replace(".java", "");
				String source = Files.readString(file);
				boolean tagged = Pattern.compile("(?m)^@Tag\\(\"slow-scripts\"\\)\\s*$").matcher(source).find();
				if (SLOW.contains(name)) {
					assertThat(tagged).as(name + " must be tagged slow-scripts").isTrue();
				} else if (!name.equals("FastProfileGuardianTest")) {
					assertThat(source).as(name + " must not be tagged").doesNotContain("@Tag(\"slow-scripts\")");
				}
			}
		}
	}
}
