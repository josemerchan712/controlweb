package dev.josemariamerchan.controlweb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.josemariamerchan.controlweb.alert.Notifier;
import dev.josemariamerchan.controlweb.alert.SendResult;

/**
 * The {@code Notifier} of the integration tests (registered as primary in {@link TestcontainersConfiguration}):
 * records every message and can be told to fail, so tests never reach Telegram.
 */
public class RecordingNotifier implements Notifier {

	private final List<String> messages = new CopyOnWriteArrayList<>();
	private volatile SendResult result = SendResult.sent();
	private volatile RuntimeException failure;

	@Override
	public SendResult send(String htmlMessage) {
		messages.add(htmlMessage);
		RuntimeException toThrow = failure;
		if (toThrow != null) {
			throw toThrow;
		}
		return result;
	}

	public void reset() {
		messages.clear();
		result = SendResult.sent();
		failure = null;
	}

	public void respondWith(SendResult newResult) {
		this.result = newResult;
	}

	public void failWith(RuntimeException exception) {
		this.failure = exception;
	}

	public List<String> messages() {
		return new ArrayList<>(messages);
	}

	/** Alerts are sent in the background: waits until at least {@code count} messages arrived (or the timeout). */
	public List<String> awaitMessages(int count, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (messages.size() < count && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		return messages();
	}
}
