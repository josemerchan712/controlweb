package dev.josemariamerchan.controlweb.incident.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;
import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.incident.rules.CheckSample;
import dev.josemariamerchan.controlweb.incident.rules.Decision;
import dev.josemariamerchan.controlweb.incident.rules.RuleConfig;
import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;
import dev.josemariamerchan.controlweb.incident.rules.RuleInput;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Connects the {@link RuleEngine} to the database: reads what the engine needs, asks for decisions and
 * applies each one through {@link IncidentWriter}. Not {@code @Transactional} itself: the read is one
 * short transaction and every change is another, so a failure in one decision affects nothing else.
 */
@Service
public class IncidentService {

	private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

	private final SiteRepository sites;
	private final CheckRepository checks;
	private final AlertRuleRepository rules;
	private final IncidentRepository incidents;
	private final RuleEngine engine;
	private final IncidentWriter writer;
	private final Clock clock;
	private final TransactionTemplate readTransaction;

	public IncidentService(SiteRepository sites, CheckRepository checks, AlertRuleRepository rules,
			IncidentRepository incidents, RuleEngine engine, IncidentWriter writer,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.sites = sites;
		this.checks = checks;
		this.rules = rules;
		this.incidents = incidents;
		this.engine = engine;
		this.writer = writer;
		this.clock = clock;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Evaluates the site's rules against its stored data and opens/closes incidents accordingly. Safe to
	 * call any number of times: the open incidents are part of what the engine reads, so a repeated
	 * evaluation changes nothing. Applies each decision independently; a failing one is logged and the
	 * rest still run.
	 */
	public void evaluate(Long siteId) {
		RuleInput input = readTransaction.execute(status -> load(siteId));
		if (input == null) {
			return; // the site is gone
		}
		for (Decision decision : engine.evaluate(input)) {
			apply(siteId, decision, input.now());
		}
	}

	/**
	 * Closes all the site's open incidents with the given reason, in the CALLER's transaction. Used when a
	 * site is deactivated so they close at that very moment, not at the next check.
	 */
	public int closeOpenIncidents(Long siteId, String reason) {
		return writer.closeAllOpen(siteId, reason, clock.instant());
	}

	private RuleInput load(Long siteId) {
		Site site = sites.findById(siteId).orElse(null);
		if (site == null) {
			return null;
		}
		List<RuleConfig> ruleConfigs = rules.findAllBySiteId(siteId).stream().map(IncidentService::toConfig).toList();
		List<CheckSample> samples = checks.findRecent(siteId, PageRequest.of(0, engine.windowSize(ruleConfigs))).stream()
				.map(check -> new CheckSample(check.getCheckedAt(), check.isUp(), check.getStatusCode(),
						check.getLatencyMs(), check.getErrorMessage(), check.getCertExpiresAt()))
				.toList();
		Set<IncidentType> open = EnumSet.noneOf(IncidentType.class);
		open.addAll(incidents.findOpenTypes(siteId));
		return new RuleInput(clock.instant(), site.isActive(), ruleConfigs, samples, open);
	}

	private static RuleConfig toConfig(AlertRule rule) {
		return new RuleConfig(rule.getType(), rule.getThreshold(), rule.isEnabled());
	}

	private void apply(Long siteId, Decision decision, Instant now) {
		try {
			switch (decision) {
				case Decision.Open open -> writer.open(siteId, open.type(), open.details(), now);
				case Decision.Close close -> writer.close(siteId, close.type(), close.reason(), now);
			}
		}
		catch (DataIntegrityViolationException ex) {
			// Another evaluation opened this incident first (unique index): nothing was stored or announced.
			log.debug("Site {}: {} was already handled by a concurrent evaluation", siteId, decision.type());
		}
		catch (SiteNotFoundException ex) {
			log.debug("Site {} was deleted during evaluation", siteId);
		}
		catch (RuntimeException ex) {
			log.warn("Site {}: could not apply {} for {}: {} {}", siteId, decision.getClass().getSimpleName(),
					decision.type(), ex.getClass().getName(), MessageSanitizer.sanitize(ex.getMessage()));
		}
	}
}
