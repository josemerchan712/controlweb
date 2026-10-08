package dev.josemariamerchan.controlweb.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;

class IncidentAlertListenerTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
	private final AlertMessageFormatter formatter = new AlertMessageFormatter();
	private final AlertDispatcher dispatcher = mock(AlertDispatcher.class);
	private final IncidentAlertListener listener = new IncidentAlertListener(formatter, dispatcher);

	private final IncidentOpened opened = new IncidentOpened(1L, 2L, "Blog", IncidentType.DOWN, T0, "Site is down");
	private final IncidentResolved resolved = new IncidentResolved(1L, 2L, "Blog", IncidentType.DOWN, T0,
			T0.plusSeconds(754), Duration.ofSeconds(754), "Site is down | Closed: Site recovered");

	@Test
	@DisplayName("an opened incident queues the opening message")
	void queuesOpened() {
		listener.onOpened(opened);

		verify(dispatcher).dispatch(formatter.opened(opened));
	}

	@Test
	@DisplayName("a resolved incident queues the closing message with its duration")
	void queuesResolved() {
		listener.onResolved(resolved);

		verify(dispatcher).dispatch(formatter.resolved(resolved));
		assertThat(formatter.resolved(resolved)).contains("12 min");
	}

	@Test
	@DisplayName("both handlers run only AFTER the incident's transaction commits")
	void handlersAreAfterCommit() throws Exception {
		for (String name : new String[] { "onOpened", "onResolved" }) {
			Method method = IncidentAlertListener.class.getMethod(name,
					name.equals("onOpened") ? IncidentOpened.class : IncidentResolved.class);
			TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

			assertThat(annotation).as(name).isNotNull();
			assertThat(annotation.phase()).as(name).isEqualTo(TransactionPhase.AFTER_COMMIT);
		}
	}

	@Test
	@DisplayName("a failure while queueing is swallowed: it never reaches the check or the incident")
	void failuresAreSwallowed() {
		doThrow(new IllegalStateException("boom")).when(dispatcher).dispatch(anyString());

		assertThatCode(() -> listener.onOpened(opened)).doesNotThrowAnyException();
		assertThatCode(() -> listener.onResolved(resolved)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a failure while formatting is swallowed too, and nothing is queued")
	void formattingFailuresAreSwallowed() {
		AlertMessageFormatter broken = mock(AlertMessageFormatter.class);
		when(broken.opened(opened)).thenThrow(new IllegalArgumentException("bad event"));
		AlertDispatcher untouched = mock(AlertDispatcher.class);

		assertThatCode(() -> new IncidentAlertListener(broken, untouched).onOpened(opened)).doesNotThrowAnyException();

		verifyNoInteractions(untouched);
	}
}
