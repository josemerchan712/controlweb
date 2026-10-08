package dev.josemariamerchan.controlweb.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;

import dev.josemariamerchan.controlweb.config.RetentionProperties;

/** The daily trigger: a configurable cron, switchable off, and a failure never escapes the scheduler thread. */
class CheckRetentionJobTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RetentionProperties.class)
	@Import(CheckRetentionJob.class)
	static class Wiring {

		@Bean
		CheckRetentionService service() {
			return mock(CheckRetentionService.class);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

	private static List<Task> tasks(org.springframework.context.ApplicationContext context) {
		return context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
				.map(ScheduledTask::getTask).toList();
	}

	@Test
	@DisplayName("schedules ONE cron task using the configured expression")
	void usesConfiguredCron() {
		runner.withPropertyValues("app.retention.cron=0 15 4 * * *").run(context -> {
			List<Task> tasks = tasks(context);
			assertThat(tasks).hasSize(1);
			assertThat(tasks.get(0)).isInstanceOf(CronTask.class);
			assertThat(((CronTask) tasks.get(0)).getExpression()).isEqualTo("0 15 4 * * *");
		});
	}

	@Test
	@DisplayName("the default runs early in the morning")
	void defaultCron() {
		runner.run(context -> assertThat(((CronTask) tasks(context).get(0)).getExpression()).isEqualTo("0 30 3 * * *"));
	}

	@Test
	@DisplayName("with retention disabled there is no job at all, so nothing is ever deleted")
	void disabledMeansNoJob() {
		runner.withPropertyValues("app.retention.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(CheckRetentionJob.class);
			assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
			org.mockito.Mockito.verifyNoInteractions(context.getBean(CheckRetentionService.class));
		});
	}

	@Test
	@DisplayName("a failure in the purge is caught: the scheduler thread survives and the next night tries again")
	void failureIsCaught() {
		runner.run(context -> {
			CheckRetentionService service = context.getBean(CheckRetentionService.class);
			when(service.purgeExpired()).thenThrow(new IllegalStateException("db down: secret-connection-detail"));

			assertThatCode(() -> context.getBean(CheckRetentionJob.class).run()).doesNotThrowAnyException();
			verify(service).purgeExpired();
		});
	}
}
