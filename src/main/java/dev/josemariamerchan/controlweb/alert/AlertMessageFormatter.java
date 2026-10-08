package dev.josemariamerchan.controlweb.alert;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

/**
 * Builds the Telegram messages (HTML parse mode, in Spanish).
 *
 * <ul>
 * <li>The site name and the incident detail are typed by users or come from the monitored sites, so they
 * are ALWAYS escaped ({@code &}, {@code <}, {@code >}): nothing they contain can become markup.</li>
 * <li>The site URL is never included, only its name. URLs that appear inside the detail (e.g. in a
 * redirect-loop error) are replaced too.</li>
 * <li>Known incident reasons are translated to Spanish here (see {@link IncidentDetailTranslator}); the database keeps
 * the original text.</li>
 * <li>The message never exceeds Telegram's 4096 characters. Truncation is done on the RAW text before
 * escaping, so an entity ({@code &amp;}) or a tag can never be cut in half.</li>
 * </ul>
 */
@Component
public class AlertMessageFormatter {

	public static final int MAX_LENGTH = 4096;
	static final int MAX_NAME_LENGTH = 200;

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);
	private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final String URL_PLACEHOLDER = "[URL omitida]";

	public String opened(IncidentOpened event) {
		// Known reasons are shown in Spanish (the stored text stays English); unknown ones are shown sanitized as they are.
		return fit(event.siteName(), IncidentDetailTranslator.translate(clean(event.details())), (name, detail) -> {
			StringBuilder text = new StringBuilder()
					.append("🔴 <b>Incidencia abierta</b>\n")
					.append("<b>Sitio:</b> ").append(escape(name)).append('\n')
					.append("<b>Tipo:</b> ").append(label(event.type()));
			if (!detail.isBlank()) {
				text.append("\n<b>Detalle:</b> ").append(escape(detail));
			}
			return text.append("\n<b>Desde:</b> ").append(WHEN.format(event.openedAt())).toString();
		});
	}

	public String resolved(IncidentResolved event) {
		return fit(event.siteName(), "", (name, detail) -> "✅ <b>Incidencia resuelta</b>\n"
				+ "<b>Sitio:</b> " + escape(name) + "\n"
				+ "<b>Tipo:</b> " + label(event.type()) + "\n"
				+ "<b>Duración:</b> " + humanDuration(event.duration()));
	}

	public String test() {
		return "🔔 <b>Mensaje de prueba</b>\nControl Web puede enviar alertas a este chat.";
	}

	static String label(IncidentType type) {
		return switch (type) {
			case DOWN -> "Caída del sitio";
			case HIGH_LATENCY -> "Latencia alta";
			case SSL_EXPIRING -> "Certificado SSL a punto de caducar";
		};
	}

	/** "45 s", "12 min", "1 h 5 min", "2 h", "1 d 3 h". */
	static String humanDuration(Duration duration) {
		long seconds = Math.max(0, duration.getSeconds());
		if (seconds < 60) {
			return seconds + " s";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + " min";
		}
		long hours = minutes / 60;
		long restMinutes = minutes % 60;
		if (hours < 24) {
			return restMinutes == 0 ? hours + " h" : hours + " h " + restMinutes + " min";
		}
		long days = hours / 24;
		long restHours = hours % 24;
		return restHours == 0 ? days + " d" : days + " d " + restHours + " h";
	}

	static String escape(String raw) {
		return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Builds the message and, if it is too long, shortens the detail (then the name) until it fits. */
	private String fit(String siteName, String details, BiFunction<String, String, String> builder) {
		String name = truncate(clean(siteName), MAX_NAME_LENGTH);
		String detail = clean(details);
		String message = builder.apply(name, detail);
		int limit = detail.length();
		while (message.length() > MAX_LENGTH && limit > 0) {
			limit = limit > 40 ? limit * 3 / 4 : 0;
			message = builder.apply(name, truncate(detail, limit));
		}
		if (message.length() > MAX_LENGTH) {
			message = builder.apply(truncate(name, 40), "");
		}
		return message;
	}

	private static String clean(String text) {
		if (text == null) {
			return "";
		}
		return WHITESPACE.matcher(URL.matcher(text).replaceAll(URL_PLACEHOLDER)).replaceAll(" ").strip();
	}

	private static String truncate(String text, int limit) {
		if (text.length() <= limit) {
			return text;
		}
		if (limit <= 0) {
			return "";
		}
		int end = limit - 1;
		if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end) + "…";
	}
}
