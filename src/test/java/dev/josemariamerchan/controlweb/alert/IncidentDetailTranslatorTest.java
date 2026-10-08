package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;

/** Known incident reasons read in Spanish in the alert; unknown ones are shown (sanitized) as they are. */
class IncidentDetailTranslatorTest {

	private static String down(String lastError) {
		return "Site is down: 2 consecutive failed checks (threshold 2). Last error: " + lastError;
	}

	private static String downEs(String lastErrorEs) {
		return "Sitio caído: 2 comprobaciones fallidas consecutivas (umbral 2). Último error: " + lastErrorEs + ".";
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"HTTP 500|respuesta HTTP 500",
			"HTTP 404|respuesta HTTP 404",
			"Request timed out after 10s|tiempo de espera agotado tras 10s",
			"Connection timed out after 5s|tiempo de conexión agotado tras 5s",
			"DNS error: could not resolve host secret-internal.example.com|error de DNS: no se pudo resolver el dominio",
			"SSL error: the certificate has expired|error SSL: el certificado ha caducado",
			"SSL error: the certificate is not valid yet|error SSL: el certificado aún no es válido",
			"SSL error: the certificate does not match the host name|error SSL: el certificado no coincide con el nombre del sitio",
			"SSL error: the certificate is not trusted|error SSL: el certificado no es de confianza",
			"SSL error: TLS handshake failed|error SSL: falló el handshake TLS",
			"Connection failed: refused or unreachable|conexión fallida: rechazada o inalcanzable",
			"Blocked: private address|bloqueado: dirección privada",
			"Too many redirects (max 5)|demasiadas redirecciones (máximo 5)",
			"Redirect loop detected at https://a.example.com/x|bucle de redirecciones detectado",
			"Redirect to an invalid location|redirección a un destino no válido",
			"Redirect to an unsupported scheme|redirección a un protocolo no admitido",
			"Redirect target contains credentials|redirección a un destino con credenciales",
			"Invalid URL|URL no válida",
			"Check interrupted|comprobación interrumpida",
			"Unexpected error: IllegalStateException|error inesperado (IllegalStateException)",
			"Request failed: SocketException|la petición falló (SocketException)" })
	@DisplayName("every known checker error inside a DOWN reason is translated")
	void knownCheckerErrors(String english, String spanish) {
		assertThat(IncidentDetailTranslator.translate(down(english))).isEqualTo(downEs(spanish));
	}

	@Test
	@DisplayName("the DNS translation does not repeat the host name")
	void dnsDropsTheHost() {
		assertThat(IncidentDetailTranslator.translate(down("DNS error: could not resolve host private.example.com")))
				.doesNotContain("private.example.com");
	}

	@Test
	@DisplayName("a DOWN reason without a last error is translated too")
	void downWithoutLastError() {
		assertThat(IncidentDetailTranslator.translate("Site is down: 3 consecutive failed checks (threshold 3)."))
				.isEqualTo("Sitio caído: 3 comprobaciones fallidas consecutivas (umbral 3).");
	}

	@Test
	@DisplayName("high latency is translated")
	void highLatency() {
		assertThat(IncidentDetailTranslator.translate(
				"High latency: the last 3 successful checks took 2500, 2600, 2700 ms (threshold 2000 ms)"))
				.isEqualTo("Latencia alta: las últimas 3 comprobaciones correctas tardaron 2500, 2600, 2700 ms (umbral 2000 ms)");
	}

	@Test
	@DisplayName("certificate expiring and expired are translated")
	void certificate() {
		Instant expiry = Instant.parse("2026-04-01T00:00:00Z");

		assertThat(IncidentDetailTranslator.translate(
				"TLS certificate expires in 9 days, on " + expiry + " (threshold 14 days)"))
				.isEqualTo("El certificado TLS caduca en 9 días, el 2026-04-01T00:00:00Z (umbral 14 días)");
		assertThat(IncidentDetailTranslator.translate("TLS certificate expired on " + expiry))
				.isEqualTo("El certificado TLS caducó el 2026-04-01T00:00:00Z");
	}

	@Test
	@DisplayName("an unknown reason is returned unchanged")
	void unknownReason() {
		assertThat(IncidentDetailTranslator.translate("Something nobody anticipated")).isEqualTo("Something nobody anticipated");
	}

	@Test
	@DisplayName("an unknown last error keeps its original text inside the translated sentence")
	void unknownLastError() {
		assertThat(IncidentDetailTranslator.translate(down("Weird new failure")))
				.isEqualTo(downEs("Weird new failure"));
	}

	@Test
	@DisplayName("null and blank are empty / unchanged without failing")
	void nullAndBlank() {
		assertThat(IncidentDetailTranslator.translate(null)).isEmpty();
		assertThat(IncidentDetailTranslator.translate("")).isEmpty();
	}

	// ---- through the real formatter: sanitizing still applies ----

	private final AlertMessageFormatter formatter = new AlertMessageFormatter();

	private static IncidentOpened opened(String details) {
		return new IncidentOpened(1L, 1L, "Mi Blog", IncidentType.DOWN, Instant.parse("2026-03-01T12:00:00Z"), details);
	}

	@Test
	@DisplayName("in the message, an unknown reason shows the ORIGINAL text, escaped and with URLs omitted")
	void unknownReasonIsSanitizedInTheMessage() {
		String message = formatter.opened(opened("Odd <b>failure</b> at https://secret.example.com/path?token=abc"));

		assertThat(message).contains("Odd &lt;b&gt;failure&lt;/b&gt; at [URL omitida]")
				.doesNotContain("secret.example.com").doesNotContain("token=abc");
	}

	@Test
	@DisplayName("in the message, a known reason is Spanish and an unknown last error is still sanitized")
	void knownReasonWithUnknownErrorIsSanitized() {
		String message = formatter.opened(opened(down("weird <i>thing</i> https://secret.example.com/x?k=1")));

		assertThat(message).contains("Sitio caído: 2 comprobaciones fallidas consecutivas (umbral 2).")
				.contains("weird &lt;i&gt;thing&lt;/i&gt; [URL omitida]")
				.doesNotContain("secret.example.com").doesNotContain("k=1");
	}
}
