package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

class AlertMessageFormatterTest {

	private static final Instant OPENED = Instant.parse("2026-03-01T12:00:00Z");
	private final AlertMessageFormatter formatter = new AlertMessageFormatter();

	private static IncidentOpened opened(String siteName, IncidentType type, String details) {
		return new IncidentOpened(7L, 3L, siteName, type, OPENED, details);
	}

	private static IncidentResolved resolved(String siteName, IncidentType type, Duration duration) {
		return new IncidentResolved(7L, 3L, siteName, type, OPENED, OPENED.plus(duration), duration, "ignored");
	}

	/** After removing the tags we use and the three entities we produce, no markup character may remain. */
	private static void assertOnlyOurMarkup(String message) {
		String withoutOurTags = message.replaceAll("</?b>", "");
		String withoutEntities = withoutOurTags.replace("&amp;", "").replace("&lt;", "").replace("&gt;", "");
		assertThat(withoutEntities).as("no raw <, > or & left in:\n%s", message).doesNotContain("<").doesNotContain(">")
				.doesNotContain("&");
	}

	// ---- content ----

	@Test
	@DisplayName("an opened incident: Spanish text with type, site name, detail and start time, in HTML")
	void openedMessage() {
		String message = formatter.opened(opened("Mi Blog", IncidentType.DOWN,
				"Site is down: 2 consecutive failed checks (threshold 2). Last error: HTTP 500"));

		assertThat(message).isEqualTo("🔴 <b>Incidencia abierta</b>\n"
				+ "<b>Sitio:</b> Mi Blog\n"
				+ "<b>Tipo:</b> Caída del sitio\n"
				+ "<b>Detalle:</b> Site is down: 2 consecutive failed checks (threshold 2). Last error: HTTP 500\n"
				+ "<b>Desde:</b> 2026-03-01 12:00 UTC");
	}

	@Test
	@DisplayName("a resolved incident shows the type, the name and the duration in a readable form")
	void resolvedMessage() {
		String message = formatter.resolved(resolved("Mi Blog", IncidentType.HIGH_LATENCY, Duration.ofSeconds(754)));

		assertThat(message).isEqualTo("✅ <b>Incidencia resuelta</b>\n"
				+ "<b>Sitio:</b> Mi Blog\n"
				+ "<b>Tipo:</b> Latencia alta\n"
				+ "<b>Duración:</b> 12 min");
	}

	@Test
	@DisplayName("each incident type has a Spanish label")
	void typeLabels() {
		assertThat(AlertMessageFormatter.label(IncidentType.DOWN)).isEqualTo("Caída del sitio");
		assertThat(AlertMessageFormatter.label(IncidentType.HIGH_LATENCY)).isEqualTo("Latencia alta");
		assertThat(AlertMessageFormatter.label(IncidentType.SSL_EXPIRING)).isEqualTo("Certificado SSL a punto de caducar");
	}

	@Test
	@DisplayName("the test message is in Spanish and plain")
	void testMessage() {
		assertThat(formatter.test()).contains("Mensaje de prueba").contains("<b>");
		assertOnlyOurMarkup(formatter.test());
	}

	@Test
	@DisplayName("an opened incident without detail has no empty 'Detalle' line")
	void openedWithoutDetail() {
		assertThat(formatter.opened(opened("Blog", IncidentType.DOWN, null))).doesNotContain("Detalle");
		assertThat(formatter.opened(opened("Blog", IncidentType.DOWN, "   "))).doesNotContain("Detalle");
	}

	@ParameterizedTest(name = "{0} s -> {1}")
	@CsvSource({ "0,0 s", "45,45 s", "59,59 s", "60,1 min", "754,12 min", "3599,59 min", "3600,1 h", "3900,1 h 5 min",
			"7200,2 h", "86399,23 h 59 min", "86400,1 d", "97200,1 d 3 h", "-5,0 s" })
	void humanDurations(long seconds, String expected) {
		assertThat(AlertMessageFormatter.humanDuration(Duration.ofSeconds(seconds))).isEqualTo(expected);
	}

	// ---- escaping ----

	@Test
	@DisplayName("the site name and the detail are ALWAYS escaped: a <script> name cannot become markup")
	void escapesUserData() {
		String message = formatter.opened(opened("<script>alert(1)</script> & Co", IncidentType.DOWN,
				"error in <b>bold</b> & more <i>stuff</i> > done"));

		assertThat(message).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; Co")
				.contains("error in &lt;b&gt;bold&lt;/b&gt; &amp; more &lt;i&gt;stuff&lt;/i&gt; &gt; done")
				.doesNotContain("<script>").doesNotContain("<i>");
		assertOnlyOurMarkup(message);
	}

	@Test
	@DisplayName("escaping also protects the resolved message")
	void escapesInResolvedMessage() {
		String message = formatter.resolved(resolved("A&B <Shop>", IncidentType.DOWN, Duration.ofMinutes(3)));

		assertThat(message).contains("A&amp;B &lt;Shop&gt;");
		assertOnlyOurMarkup(message);
	}

	@Test
	@DisplayName("text that already looks escaped is escaped again, so it shows literally")
	void doubleEscaping() {
		assertThat(formatter.opened(opened("Q&A &lt;3", IncidentType.DOWN, null))).contains("Q&amp;A &amp;lt;3");
	}

	// ---- no URLs ----

	@Test
	@DisplayName("the site URL is never included: URLs in the detail are replaced and only the name is shown")
	void neverContainsUrls() {
		String message = formatter.opened(opened("Shop", IncidentType.DOWN,
				"Redirect loop detected at https://shop.example.com/a/b and http://other.example.org:8080/x"));

		assertThat(message).contains("[URL omitida]").doesNotContain("http").doesNotContain("example.com")
				.doesNotContain("example.org");
	}

	@Test
	@DisplayName("even a site NAME that is a URL is not echoed as one")
	void urlAsNameIsReplaced() {
		assertThat(formatter.opened(opened("https://secret.example.com/?token=abc", IncidentType.DOWN, null)))
				.doesNotContain("secret.example.com").doesNotContain("token=");
	}

	// ---- length ----

	@Test
	@DisplayName("a huge detail is cut so the message fits Telegram's 4096 characters")
	void cutsLongDetails() {
		String message = formatter.opened(opened("Blog", IncidentType.DOWN, "x".repeat(50_000)));

		assertThat(message.length()).isLessThanOrEqualTo(AlertMessageFormatter.MAX_LENGTH);
		assertThat(message).contains("…").contains("<b>Desde:</b>");
		assertOnlyOurMarkup(message);
	}

	@Test
	@DisplayName("cutting happens before escaping, so an entity or tag is never left half-written")
	void neverCutsAnEntity() {
		for (String nasty : new String[] { "&".repeat(5000), "<".repeat(5000), "&<>".repeat(3000), "<b>".repeat(4000) }) {
			String message = formatter.opened(opened("A&B", IncidentType.DOWN, nasty));

			assertThat(message.length()).isLessThanOrEqualTo(AlertMessageFormatter.MAX_LENGTH);
			assertOnlyOurMarkup(message);
		}
	}

	@Test
	@DisplayName("a huge site name is shortened as well, and the message still fits")
	void cutsLongNames() {
		String message = formatter.opened(opened("N".repeat(20_000), IncidentType.DOWN, "d".repeat(20_000)));
		String resolvedMessage = formatter.resolved(resolved("&".repeat(20_000), IncidentType.DOWN, Duration.ofHours(30)));

		assertThat(message.length()).isLessThanOrEqualTo(AlertMessageFormatter.MAX_LENGTH);
		assertThat(resolvedMessage.length()).isLessThanOrEqualTo(AlertMessageFormatter.MAX_LENGTH);
		assertThat(resolvedMessage).contains("1 d 6 h");
		assertOnlyOurMarkup(resolvedMessage);
		Pattern nameLine = Pattern.compile("<b>Sitio:</b> (.*)");
		var matcher = nameLine.matcher(message);
		assertThat(matcher.find()).isTrue();
		assertThat(matcher.group(1).length()).isLessThanOrEqualTo(AlertMessageFormatter.MAX_NAME_LENGTH + 1);
	}

	@Test
	@DisplayName("normal messages are far below the limit and untouched")
	void normalMessagesAreNotCut() {
		String detail = "d".repeat(500);

		assertThat(formatter.opened(opened("Blog", IncidentType.DOWN, detail))).contains(detail).doesNotContain("…");
	}

	@Test
	@DisplayName("line breaks and runs of spaces inside user data are flattened")
	void flattensWhitespace() {
		String message = formatter.opened(opened("Blog\nHack\t<b>", IncidentType.DOWN, "line 1\n\n\nline 2"));

		assertThat(message).contains("<b>Sitio:</b> Blog Hack &lt;b&gt;\n").contains("line 1 line 2");
	}
}
