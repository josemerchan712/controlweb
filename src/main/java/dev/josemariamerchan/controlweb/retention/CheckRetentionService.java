package dev.josemariamerchan.controlweb.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.josemariamerchan.controlweb.config.RetentionProperties;

/**
 * Deletes checks strictly older than {@code app.retention.days}, measured with the application's Clock.
 *
 * <p>Rows go in batches, each in its own short transaction, so a large backlog never holds one long lock or
 * produces one huge transaction while checks are still being written. Incidents are not touched (their history
 * is tiny and is the useful record); alert rules and sites are not related to this table's age.
 * Not {@code @Transactional} as a whole on purpose.
 */
@Service
public class CheckRetentionService {

	private static final Logger log = LoggerFactory.getLogger(CheckRetentionService.class);

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final RetentionProperties properties;

	public CheckRetentionService(JdbcClient jdbc, TransactionTemplate transaction, Clock clock, RetentionProperties properties) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.clock = clock;
		this.properties = properties;
	}

	/** @return how many checks were deleted */
	public long purgeExpired() {
		Instant cutoff = clock.instant().minus(Duration.ofDays(properties.days()));
		long total = 0;
		int deleted;
		do {
			Integer batch = transaction.execute(status -> deleteBatch(cutoff));
			deleted = batch == null ? 0 : batch;
			total += deleted;
		}
		while (deleted >= properties.batchSize());
		log.info("Retention: deleted {} checks older than {} days", total, properties.days());
		return total;
	}

	private int deleteBatch(Instant cutoff) {
		return jdbc.sql("""
				delete from checks
				where id in (select id from checks where checked_at < :cutoff order by id limit :batch)
				""")
				.param("cutoff", java.sql.Timestamp.from(cutoff))
				.param("batch", properties.batchSize())
				.update();
	}
}
