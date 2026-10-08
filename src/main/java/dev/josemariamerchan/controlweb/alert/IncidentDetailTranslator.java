package dev.josemariamerchan.controlweb.alert;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the (English) incident details produced by the rule engine and the checker into Spanish, for the Telegram
 * message ONLY. The database keeps the original text; this only changes how an alert reads.
 *
 * <p>Every known reason matches a full, anchored pattern, so nothing is "partly" translated. A reason that is not
 * recognised is returned unchanged (the formatter still sanitizes and escapes it). The DNS translation drops the
 * host name on purpose: the alert already names the site, and the host is not needed to act on it.
 */
final class IncidentDetailTranslator {

	private record Rule(Pattern pattern, Function<Matcher, String> spanish) {

		static Rule of(String regex, Function<Matcher, String> spanish) {
			return new Rule(Pattern.compile(regex, Pattern.DOTALL), spanish);
		}

		static Rule fixed(String english, String spanish) {
			return of(Pattern.quote(english), m -> spanish);
		}
	}

	/** What the checker stores in {@code checks.error_message} (see HttpSiteChecker). */
	private static final List<Rule> CHECK_ERRORS = List.of(
			Rule.of("HTTP (\\d{3})", m -> "respuesta HTTP " + m.group(1)),
			Rule.of("Request timed out after (\\d+(?:ms|s))", m -> "tiempo de espera agotado tras " + m.group(1)),
			Rule.of("Connection timed out after (\\d+(?:ms|s))", m -> "tiempo de conexión agotado tras " + m.group(1)),
			Rule.of("DNS error: could not resolve host .*", m -> "error de DNS: no se pudo resolver el dominio"),
			Rule.fixed("SSL error: the certificate has expired", "error SSL: el certificado ha caducado"),
			Rule.fixed("SSL error: the certificate is not valid yet", "error SSL: el certificado aún no es válido"),
			Rule.fixed("SSL error: the certificate does not match the host name",
					"error SSL: el certificado no coincide con el nombre del sitio"),
			Rule.fixed("SSL error: the certificate is not trusted", "error SSL: el certificado no es de confianza"),
			Rule.fixed("SSL error: TLS handshake failed", "error SSL: falló el handshake TLS"),
			Rule.fixed("Connection failed: refused or unreachable", "conexión fallida: rechazada o inalcanzable"),
			Rule.fixed("Blocked: private address", "bloqueado: dirección privada"),
			Rule.of("Too many redirects \\(max (\\d+)\\)", m -> "demasiadas redirecciones (máximo " + m.group(1) + ")"),
			Rule.of("Redirect loop detected at .*", m -> "bucle de redirecciones detectado"),
			Rule.fixed("Redirect to an invalid location", "redirección a un destino no válido"),
			Rule.fixed("Redirect to an unsupported scheme", "redirección a un protocolo no admitido"),
			Rule.fixed("Redirect target contains credentials", "redirección a un destino con credenciales"),
			Rule.fixed("Invalid URL", "URL no válida"),
			Rule.fixed("Check interrupted", "comprobación interrumpida"),
			Rule.of("Unexpected error: (\\w+)", m -> "error inesperado (" + m.group(1) + ")"),
			Rule.of("Request failed: (\\w+)", m -> "la petición falló (" + m.group(1) + ")"));

	private static final Pattern DOWN = Pattern.compile(
			"Site is down: (\\d+) consecutive failed checks \\(threshold (\\d+)\\)\\.(?: Last error: (.*))?", Pattern.DOTALL);

	/** What the rule engine stores in {@code incidents.details} when it OPENS an incident. */
	private static final List<Rule> INCIDENTS = List.of(
			Rule.of("High latency: the last (\\d+) successful checks took ([\\d, ]+) ms \\(threshold (\\d+) ms\\)",
					m -> "Latencia alta: las últimas %s comprobaciones correctas tardaron %s ms (umbral %s ms)"
							.formatted(m.group(1), m.group(2), m.group(3))),
			Rule.of("TLS certificate expired on (\\S+)", m -> "El certificado TLS caducó el " + m.group(1)),
			Rule.of("TLS certificate expires in (\\d+) days, on (\\S+) \\(threshold (\\d+) days\\)",
					m -> "El certificado TLS caduca en %s días, el %s (umbral %s días)".formatted(m.group(1), m.group(2),
							m.group(3))));

	private IncidentDetailTranslator() {
	}

	/** @return the Spanish text for a known reason, or {@code detail} itself when it is not recognised */
	static String translate(String detail) {
		if (detail == null) {
			return "";
		}
		String text = detail.strip();
		Matcher down = DOWN.matcher(text);
		if (down.matches()) {
			String translated = "Sitio caído: %s comprobaciones fallidas consecutivas (umbral %s)."
					.formatted(down.group(1), down.group(2));
			return down.group(3) == null ? translated : translated + " Último error: " + translateError(down.group(3)) + ".";
		}
		for (Rule rule : INCIDENTS) {
			Matcher matcher = rule.pattern().matcher(text);
			if (matcher.matches()) {
				return rule.spanish().apply(matcher);
			}
		}
		return detail;
	}

	/** An unknown checker error is kept as it is (it is sanitized by the formatter). */
	private static String translateError(String error) {
		String text = error.strip();
		for (Rule rule : CHECK_ERRORS) {
			Matcher matcher = rule.pattern().matcher(text);
			if (matcher.matches()) {
				return rule.spanish().apply(matcher);
			}
		}
		return text;
	}
}
