package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import dev.josemariamerchan.controlweb.LogCapture;
import dev.josemariamerchan.controlweb.config.AlertProperties;

/** Alerts are sent in the background, in order, and nothing the notifier does can reach the caller. */
class AlertDispatcherTest {

	private final List<AlertDispatcher> dispatchers = new ArrayList<>();

	@AfterEach
	void tearDown() {
		dispatchers.forEach(AlertDispatcher::shutdown);
	}

	private AlertDispatcher dispatcher(Notifier notifier, int queueCapacity, Duration shutdownTimeout) {
		AlertDispatcher dispatcher = new AlertDispatcher(notifier,
				new AlertProperties(queueCapacity, shutdownTimeout, Duration.ofSeconds(10)));
		dispatchers.add(dispatcher);
		return dispatcher;
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertThat(latch.await(5, TimeUnit.SECONDS)).as("timed out waiting for the background sender").isTrue();
	}

	@Test
	@DisplayName("dispatch returns immediately even when the notifier is stuck: the caller is never blocked")
	void dispatchNeverBlocks() throws Exception {
		CountDownLatch inNotifier = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AlertDispatcher dispatcher = dispatcher(message -> {
			inNotifier.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return SendResult.sent();
		}, 10, Duration.ofSeconds(5));

		long started = System.nanoTime();
		dispatcher.dispatch("one");
		await(inNotifier);
		dispatcher.dispatch("two");
		dispatcher.dispatch("three");
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(elapsedMs).as("three dispatches while the notifier is blocked").isLessThan(1000);
		release.countDown();
	}

	@Test
	@DisplayName("alerts are delivered on another thread (a virtual one), never on the caller's")
	void deliversOnAnotherVirtualThread() throws Exception {
		CountDownLatch delivered = new CountDownLatch(1);
		List<Thread> threads = new CopyOnWriteArrayList<>();
		AlertDispatcher dispatcher = dispatcher(message -> {
			threads.add(Thread.currentThread());
			delivered.countDown();
			return SendResult.sent();
		}, 10, Duration.ofSeconds(5));

		dispatcher.dispatch("hello");
		await(delivered);

		assertThat(threads).singleElement().satisfies(thread -> {
			assertThat(thread).isNotSameAs(Thread.currentThread());
			assertThat(thread.isVirtual()).isTrue();
			assertThat(thread.getName()).startsWith("alert-sender-");
		});
	}

	@Test
	@DisplayName("alerts go out in the order they were queued (an 'opened' is never overtaken by its 'resolved')")
	void keepsOrder() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch all = new CountDownLatch(20);
		AlertDispatcher dispatcher = dispatcher(message -> {
			received.add(message);
			all.countDown();
			return SendResult.sent();
		}, 50, Duration.ofSeconds(5));

		for (int i = 0; i < 20; i++) {
			dispatcher.dispatch("message-" + i);
		}
		await(all);

		assertThat(received).containsExactly(java.util.stream.IntStream.range(0, 20).mapToObj(i -> "message-" + i).toArray(String[]::new));
	}

	@Test
	@DisplayName("a notifier that throws is logged by type and does not stop later alerts")
	void notifierFailureIsContained() throws Exception {
		CountDownLatch second = new CountDownLatch(1);
		AtomicBoolean firstCalled = new AtomicBoolean();
		AlertDispatcher dispatcher = dispatcher(message -> {
			if (message.equals("first")) {
				firstCalled.set(true);
				throw new IllegalStateException("boom talking to https://api.telegram.org/bot123:SECRET/sendMessage");
			}
			second.countDown();
			return SendResult.sent();
		}, 10, Duration.ofSeconds(5));

		try (LogCapture logs = LogCapture.of(AlertDispatcher.class, Level.DEBUG)) {
			assertThatCode(() -> dispatcher.dispatch("first")).doesNotThrowAnyException();
			dispatcher.dispatch("second");
			await(second);

			assertThat(firstCalled).isTrue();
			assertThat(logs.messages()).anyMatch(message -> message.contains("IllegalStateException"));
			assertThat(String.join("\n", logs.fullText())).doesNotContain("SECRET").doesNotContain("/sendMessage");
		}
	}

	@Test
	@DisplayName("when the queue is full, new alerts are dropped with a warning instead of blocking or growing without limit")
	void fullQueueDropsAlerts() throws Exception {
		CountDownLatch inNotifier = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<String> received = new CopyOnWriteArrayList<>();
		AlertDispatcher dispatcher = dispatcher(message -> {
			received.add(message);
			inNotifier.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return SendResult.sent();
		}, 2, Duration.ofSeconds(5));

		try (LogCapture logs = LogCapture.of(AlertDispatcher.class, Level.DEBUG)) {
			dispatcher.dispatch("in-flight");
			await(inNotifier);
			dispatcher.dispatch("queued-1");
			dispatcher.dispatch("queued-2");
			assertThatCode(() -> {
				dispatcher.dispatch("dropped-1");
				dispatcher.dispatch("dropped-2");
			}).doesNotThrowAnyException();

			assertThat(logs.messages().stream().filter(message -> message.contains("Alert dropped"))).hasSize(2);
			release.countDown();
			dispatcher.shutdown();
			assertThat(received).containsExactly("in-flight", "queued-1", "queued-2");
		}
	}

	@Test
	@DisplayName("shutdown lets queued alerts go out first, and later dispatches are dropped quietly")
	void shutdownDrainsTheQueue() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		AlertDispatcher dispatcher = dispatcher(message -> {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			received.add(message);
			return SendResult.sent();
		}, 10, Duration.ofSeconds(5));

		dispatcher.dispatch("a");
		dispatcher.dispatch("b");
		dispatcher.dispatch("c");
		dispatcher.shutdown();

		assertThat(received).containsExactly("a", "b", "c");
		assertThatCode(() -> dispatcher.dispatch("too late")).doesNotThrowAnyException();
		assertThat(received).doesNotContain("too late");
	}

	@Test
	@DisplayName("shutdown does not wait forever for a stuck notifier")
	void shutdownTimesOut() throws Exception {
		CountDownLatch inNotifier = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AlertDispatcher dispatcher = dispatcher(message -> {
			inNotifier.countDown();
			try {
				Thread.sleep(30_000);
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
			}
			return SendResult.sent();
		}, 10, Duration.ofMillis(200));
		dispatcher.dispatch("stuck");
		await(inNotifier);

		long started = System.nanoTime();
		dispatcher.shutdown();
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(elapsedMs).isBetween(150L, 3000L);
		assertThat(interrupted.get() || elapsedMs < 3000).isTrue();
	}
}
