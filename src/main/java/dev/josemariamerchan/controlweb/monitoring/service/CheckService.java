package dev.josemariamerchan.controlweb.monitoring.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.josemariamerchan.controlweb.monitoring.checker.CheckResult;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpSiteChecker;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckResponse;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Runs one check of a site and stores the outcome.
 *
 * <p>This class is deliberately NOT {@code @Transactional}. A check involves a slow network call and
 * must not hold a transaction (and therefore a pooled database connection) while it waits, so the
 * work is split in three: a short read transaction that gets the URL, the network check with no
 * transaction at all, and a short write transaction that saves the result.
 */
@Service
public class CheckService {

	private final SiteRepository sites;
	private final CheckRepository checks;
	private final HttpSiteChecker checker;
	private final Clock clock;
	private final TransactionTemplate writeTransaction;
	private final TransactionTemplate readTransaction;

	public CheckService(SiteRepository sites, CheckRepository checks, HttpSiteChecker checker,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.sites = sites;
		this.checks = checks;
		this.checker = checker;
		this.clock = clock;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	private record Target(String url) {
	}

	/** Ids of the active sites that are due for a check according to the injected clock. */
	public List<Long> findDueSiteIds() {
		return readTransaction.execute(status -> sites.findDueSiteIds(clock.instant()));
	}

	/**
	 * Checks the site now and stores the result. {@code last_checked_at} is updated whatever the
	 * outcome, including failures.
	 *
	 * @throws SiteNotFoundException if the site does not exist, or was deleted while it was being checked
	 */
	public CheckResponse runCheck(Long siteId) {
		Target target = readTransaction.execute(status -> sites.findById(siteId)
				.map(site -> new Target(site.getUrl())).orElse(null));
		if (target == null) {
			throw new SiteNotFoundException(siteId);
		}

		Instant checkedAt = clock.instant();
		CheckResult result = checker.check(target.url()); // no transaction, no database connection held here

		return save(siteId, checkedAt, result);
	}

	private CheckResponse save(Long siteId, Instant checkedAt, CheckResult result) {
		try {
			return writeTransaction.execute(status -> {
				Site site = sites.findById(siteId).orElseThrow(() -> new SiteNotFoundException(siteId));
				site.markChecked(checkedAt);
				return CheckResponse.from(checks.saveAndFlush(toEntity(site, checkedAt, result)));
			});
		}
		catch (DataIntegrityViolationException ex) {
			// The site vanished between loading it and committing (foreign key violation).
			if (!sites.existsById(siteId)) {
				throw new SiteNotFoundException(siteId);
			}
			throw ex;
		}
	}

	private static Check toEntity(Site site, Instant checkedAt, CheckResult result) {
		if (result.up()) {
			return Check.up(site, checkedAt, result.statusCode(), result.latencyMs(), result.certExpiresAt());
		}
		return Check.down(site, checkedAt, result.statusCode(), result.latencyMs(), result.errorMessage(),
				result.certExpiresAt());
	}
}
