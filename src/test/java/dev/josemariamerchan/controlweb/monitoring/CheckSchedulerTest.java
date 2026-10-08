package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;

import dev.josemariamerchan.controlweb.config.SchedulerProperties;
import dev.josemariamerchan.controlweb.heartbeat.Heartbeat;
import dev.josemariamerchan.controlweb.monitoring.scheduler.CheckScheduler;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;

/** How the periodic trigger is wired: fixed delay (never fixed rate), configurable, and switchable off. */
class CheckSchedulerTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SchedulerProperties.class)
	@Import(CheckScheduler.class)
	static class Wiring {

		@Bean
		CheckRunner runner() {
			return mock(CheckRunner.class);
		}

		@Bean
		Heartbeat heartbeat() {
			return mock(Heartbeat.class);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class)
			// far enough in the future that the real task never fires during the test
			.withPropertyValues("app.scheduler.initial-delay=1h");

	private static List<Task> scheduledTasks(org.springframework.context.ApplicationContext context) {
		return context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
				.map(ScheduledTask::getTask).toList();
	}

	@Test
	@DisplayName("the periodic task is a FIXED DELAY task using the configured delay (never fixed rate)")
	void usesFixedDelay() {
		runner.withPropertyValues("app.scheduler.fixed-delay=7s").run(context -> {
			List<Task> tasks = scheduledTasks(context);

			assertThat(tasks).hasSize(1);
			assertThat(tasks.get(0)).isInstanceOf(FixedDelayTask.class);
			assertThat(tasks).noneMatch(task -> task instanceof FixedRateTask);
			FixedDelayTask task = (FixedDelayTask) tasks.get(0);
			assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(7));
			assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
		});
	}

	@Test
	@DisplayName("by default it runs every 10 seconds")
	void defaultDelayIsTenSeconds() {
		runner.run(context -> assertThat(((FixedDelayTask) scheduledTasks(context).get(0)).getIntervalDuration())
				.isEqualTo(Duration.ofSeconds(10)));
	}

	@Test
	@DisplayName("with app.scheduler.enabled=false there is no scheduler bean and nothing is scheduled")
	void disabledMeansNoScheduler() {
		runner.withPropertyValues("app.scheduler.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(CheckScheduler.class);
			assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
		});
	}

	@Test
	@DisplayName("the scheduled method just triggers a cycle of the runner")
	void scheduledMethodRunsACycle() {
		CheckRunner mockRunner = mock(CheckRunner.class);

		new CheckScheduler(mockRunner, mock(Heartbeat.class)).runDueChecks();

		org.mockito.Mockito.verify(mockRunner).runCycle();
	}

	@Test
	@DisplayName("after a cycle that ran, the scheduler beats the heartbeat")
	void heartbeatAfterACycle() {
		CheckRunner mockRunner = mock(CheckRunner.class);
		Heartbeat heartbeat = mock(Heartbeat.class);
		org.mockito.Mockito.when(mockRunner.runCycle()).thenReturn(true);

		new CheckScheduler(mockRunner, heartbeat).runDueChecks();

		org.mockito.Mockito.verify(heartbeat).beat();
	}

	@Test
	@DisplayName("when the cycle could not run (database down, shutting down) there is NO heartbeat: silence is the alarm")
	void noHeartbeatWhenTheCycleDidNotRun() {
		CheckRunner mockRunner = mock(CheckRunner.class);
		Heartbeat heartbeat = mock(Heartbeat.class);
		org.mockito.Mockito.when(mockRunner.runCycle()).thenReturn(false);

		new CheckScheduler(mockRunner, heartbeat).runDueChecks();

		org.mockito.Mockito.verifyNoInteractions(heartbeat);
	}

	@Test
	@DisplayName("a heartbeat that throws can never stop the scheduler")
	void heartbeatFailureIsContained() {
		CheckRunner mockRunner = mock(CheckRunner.class);
		Heartbeat heartbeat = mock(Heartbeat.class);
		org.mockito.Mockito.when(mockRunner.runCycle()).thenReturn(true);
		org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(heartbeat).beat();

		org.assertj.core.api.Assertions.assertThatCode(() -> new CheckScheduler(mockRunner, heartbeat).runDueChecks())
				.doesNotThrowAnyException();
	}

	// ---- SchedulerProperties ----

	@Test
	@DisplayName("defaults: enabled, 5 s initial delay, 10 s fixed delay, 10 concurrent checks, 10 s shutdown wait")
	void propertyDefaults() {
		runner.run(context -> {
			SchedulerProperties properties = context.getBean(SchedulerProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(10));
			assertThat(properties.maxConcurrency()).isEqualTo(10);
			assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
		});
	}

	@Test
	@DisplayName("the concurrency limit and shutdown wait are configurable")
	void propertyOverrides() {
		runner.withPropertyValues("app.scheduler.max-concurrency=4", "app.scheduler.shutdown-timeout=3s").run(context -> {
			assertThat(context.getBean(SchedulerProperties.class).maxConcurrency()).isEqualTo(4);
			assertThat(context.getBean(SchedulerProperties.class).shutdownTimeout()).isEqualTo(Duration.ofSeconds(3));
		});
	}

	@ParameterizedTest(name = "{0} is rejected")
	@ValueSource(strings = { "app.scheduler.max-concurrency=0", "app.scheduler.max-concurrency=201",
			"app.scheduler.fixed-delay=0s", "app.scheduler.fixed-delay=-5s", "app.scheduler.shutdown-timeout=-1s" })
	void invalidPropertiesAreRejected(String property) {
		runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}
}
