package dev.josemariamerchan.controlweb.monitoring.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;

/**
 * The periodic trigger. It only exists when {@code app.scheduler.enabled} is true (the default; tests
 * turn it off). {@code fixedDelay} counts from the END of a cycle, so a slow cycle never overlaps with
 * the next one (with {@code fixedRate} they would pile up).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckScheduler {

	private final CheckRunner runner;

	public CheckScheduler(CheckRunner runner) {
		this.runner = runner;
	}

	@Scheduled(initialDelayString = "${app.scheduler.initial-delay:5s}",
			fixedDelayString = "${app.scheduler.fixed-delay:10s}")
	public void runDueChecks() {
		runner.runCycle();
	}
}
