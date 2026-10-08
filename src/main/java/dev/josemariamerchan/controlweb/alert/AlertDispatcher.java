package dev.josemariamerchan.controlweb.alert;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.AlertProperties;
import jakarta.annotation.PreDestroy;

/**
 * Sends alerts in the background so that neither the check that raised them nor the scheduler ever
 * waits for the network. A single virtual thread drains a bounded queue, which keeps alerts in order
 * (an "opened" always goes out before its "resolved"). If Telegram is down for long the queue fills
 * and new alerts are dropped with a warning instead of piling up in memory or blocking anyone.
 */
@Component
public class AlertDispatcher {

	private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

	private final Notifier notifier;
	private final AlertProperties properties;
	private final ThreadPoolExecutor executor;

	public AlertDispatcher(Notifier notifier, AlertProperties properties) {
		this.notifier = notifier;
		this.properties = properties;
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(properties.queueCapacity()),
				Thread.ofVirtual().name("alert-sender-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
	}

	/** Queues the message and returns at once. Never throws. */
	public void dispatch(String htmlMessage) {
		try {
			executor.execute(() -> deliver(htmlMessage));
		}
		catch (RejectedExecutionException ex) {
			log.warn("Alert dropped: the alert queue is full or the application is shutting down");
		}
	}

	private void deliver(String htmlMessage) {
		try {
			SendResult result = notifier.send(htmlMessage);
			log.debug("Alert processed: {}", result.status());
		}
		catch (RuntimeException ex) {
			// A Notifier must not throw, but if one does it must not kill the sender thread.
			log.warn("The notifier failed unexpectedly: {}", ex.getClass().getName());
		}
	}

	/** Gives queued alerts a few seconds to go out, then abandons the rest. */
	@PreDestroy
	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				List<Runnable> abandoned = executor.shutdownNow();
				log.warn("Shutdown timeout reached: {} queued alert(s) were not sent", abandoned.size());
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
