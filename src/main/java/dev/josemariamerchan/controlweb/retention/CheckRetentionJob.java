package dev.josemariamerchan.controlweb.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily trigger of the purge. It only exists when {@code app.retention.enabled} is true. A failure is logged
 * (class name only: exception messages can carry connection details) and swallowed, so the scheduler thread keeps
 * going and the next night tries again.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckRetentionJob {

	private static final Logger log = LoggerFactory.getLogger(CheckRetentionJob.class);

	private final CheckRetentionService service;

	public CheckRetentionJob(CheckRetentionService service) {
		this.service = service;
	}

	@Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
	public void run() {
		try {
			service.purgeExpired();
		}
		catch (RuntimeException ex) {
			log.error("Retention purge failed ({}); it will be retried at the next scheduled run", ex.getClass().getSimpleName());
		}
	}
}
