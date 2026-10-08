package dev.josemariamerchan.controlweb.site.service;

import java.net.URI;
import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.common.net.HostSafetyValidator;
import dev.josemariamerchan.controlweb.config.AlertDefaultsProperties;
import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;
import dev.josemariamerchan.controlweb.incident.service.IncidentService;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.dto.SiteRequest;
import dev.josemariamerchan.controlweb.site.dto.SiteResponse;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

@Service
@Transactional
public class SiteService {

	private final SiteRepository sites;
	private final AlertRuleRepository alertRules;
	private final HostSafetyValidator hostSafety;
	private final AlertDefaultsProperties alertDefaults;
	private final IncidentService incidentService;
	private final Clock clock;

	public SiteService(SiteRepository sites, AlertRuleRepository alertRules, HostSafetyValidator hostSafety,
			AlertDefaultsProperties alertDefaults, IncidentService incidentService, Clock clock) {
		this.sites = sites;
		this.alertRules = alertRules;
		this.hostSafety = hostSafety;
		this.alertDefaults = alertDefaults;
		this.incidentService = incidentService;
		this.clock = clock;
	}

	public SiteResponse create(SiteRequest request) {
		URI url = validatedUrl(request.url());
		if (sites.existsByUrl(url.toString())) {
			throw new DuplicateSiteException(url.toString());
		}
		Site site = new Site(request.name().strip(), url.toString(), request.checkIntervalSeconds(),
				request.active() == null || request.active(), Boolean.TRUE.equals(request.publicVisible()),
				clock.instant());
		Site saved;
		try {
			saved = sites.saveAndFlush(site);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent insert of the same URL: the UNIQUE constraint is the backstop.
			throw new DuplicateSiteException(url.toString());
		}
		alertRules.saveAll(defaultRulesFor(saved));
		return SiteResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<SiteResponse> findAll() {
		return sites.findAll(Sort.by("name").ascending()).stream().map(SiteResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public SiteResponse findById(Long id) {
		return SiteResponse.from(sites.findById(id).orElseThrow(() -> new SiteNotFoundException(id)));
	}

	public SiteResponse update(Long id, SiteRequest request) {
		Site site = sites.findById(id).orElseThrow(() -> new SiteNotFoundException(id));
		URI url = validatedUrl(request.url());
		if (sites.existsByUrlAndIdNot(url.toString(), id)) {
			throw new DuplicateSiteException(url.toString());
		}
		boolean wasActive = site.isActive();
		site.update(request.name().strip(), url.toString(), request.checkIntervalSeconds(),
				request.active() == null ? wasActive : request.active(),
				request.publicVisible() == null ? site.isPublicVisible() : request.publicVisible(),
				clock.instant());
		if (wasActive && !site.isActive()) {
			// Close its incidents now, in this same transaction, not at the next check.
			incidentService.closeOpenIncidents(id, RuleEngine.SITE_DEACTIVATED);
		}
		return SiteResponse.from(site);
	}

	/**
	 * Deleting a site is one {@code deleteById}: its checks, alert rules and incidents are removed
	 * by {@code ON DELETE CASCADE} in the database, so no child is touched from Java.
	 */
	public void delete(Long id) {
		if (!sites.existsById(id)) {
			throw new SiteNotFoundException(id);
		}
		sites.deleteById(id);
	}

	private URI validatedUrl(String rawUrl) {
		URI url = SiteUrlNormalizer.normalize(rawUrl);
		hostSafety.assertSafe(url.getHost());
		return url;
	}

	private List<AlertRule> defaultRulesFor(Site site) {
		return List.of(
				new AlertRule(site, IncidentType.DOWN, alertDefaults.downConsecutiveFailures()),
				new AlertRule(site, IncidentType.HIGH_LATENCY, alertDefaults.highLatencyMs()),
				new AlertRule(site, IncidentType.SSL_EXPIRING, alertDefaults.sslExpiryDays()));
	}
}
