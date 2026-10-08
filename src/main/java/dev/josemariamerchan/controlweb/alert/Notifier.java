package dev.josemariamerchan.controlweb.alert;

/**
 * Delivers an alert to people. The text is Telegram-flavoured HTML ({@code <b>}, escaped {@code &lt;});
 * implementations that are not Telegram may show it as it is.
 */
public interface Notifier {

	/**
	 * Sends the alert. <b>Never throws</b>: every problem is reported in the {@link SendResult}.
	 * May block (network), so callers on request/check threads must not call it directly; use
	 * {@link AlertDispatcher}.
	 */
	SendResult send(String htmlMessage);
}
