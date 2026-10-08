package dev.josemariamerchan.controlweb.incident.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.dto.RuleRequest;
import dev.josemariamerchan.controlweb.incident.dto.RuleResponse;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Reads and tunes a site's alert rules. A change is NOT evaluated on the spot: it applies at the next
 * check, so editing a threshold never opens or closes an incident by itself.
 */
@Service
@Transactional
public class RuleService {

	private final SiteRepository sites;
	private final AlertRuleRepository rules;

	public RuleService(SiteRepository sites, AlertRuleRepository rules) {
		this.sites = sites;
		this.rules = rules;
	}

	@Transactional(readOnly = true)
	public List<RuleResponse> list(Long siteId) {
		requireSite(siteId);
		return rules.findAllBySiteId(siteId).stream().map(RuleResponse::from).toList();
	}

	public RuleResponse update(Long siteId, IncidentType type, RuleRequest request) {
		Site site = sites.findById(siteId).orElseThrow(() -> new SiteNotFoundException(siteId));
		int threshold = request.threshold();
		if (threshold < type.minThreshold() || threshold > type.maxThreshold()) {
			throw new InvalidRuleException("The threshold of %s must be between %d and %d (%s)".formatted(type,
					type.minThreshold(), type.maxThreshold(), type.unit()));
		}
		AlertRule rule = rules.findBySiteIdAndType(siteId, type).orElseGet(() -> new AlertRule(site, type, threshold));
		rule.update(threshold, request.enabled());
		return RuleResponse.from(rules.save(rule));
	}

	private void requireSite(Long siteId) {
		if (!sites.existsById(siteId)) {
			throw new SiteNotFoundException(siteId);
		}
	}
}
