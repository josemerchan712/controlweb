package dev.josemariamerchan.controlweb.monitoring.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.heartbeat.Heartbeat;
import dev.josemariamerchan.controlweb.monitoring.service.CheckRunner;

/**
 * The periodic trigger. It only exists when {@code app.scheduler.enabled} is true (the default; tests
 * turn it off). {@code fixedDelay} counts from the END of a cycle, so a slow cycle never overlaps with
 * the next one (with {@code fixedRate} they would pile up). After each cycle that ran it beats the (optional) heartbeat.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckScheduler {

	private static final Logger log = LoggerFactory.getLogger(CheckScheduler.class);

	private final CheckRunner runner;
	private final Heartbeat heartbeat;

	public CheckScheduler(CheckRunner runner, Heartbeat heartbeat) {
		this.runner = runner;
		this.heartbeat = heartbeat;
	}

	@Scheduled(initialDelayString = "${app.scheduler.initial-delay:5s}",
			fixedDelayString = "${app.scheduler.fixed-delay:10s}")
	public void runDueChecks() {
		if (runner.runCycle()) {
			// Only a cycle that really ran proves the monitor is alive; with the database down there is no ping and the
			// external service notices the silence.
			try {
				heartbeat.beat();
			}
			catch (RuntimeException ex) {
				log.warn("The heartbeat failed unexpectedly: {}", ex.getClass().getSimpleName());
			}
		}
	}
}
