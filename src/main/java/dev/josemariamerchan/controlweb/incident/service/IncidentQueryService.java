package dev.josemariamerchan.controlweb.incident.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.common.web.PageResponse;
import dev.josemariamerchan.controlweb.common.web.QueryParams;
import dev.josemariamerchan.controlweb.config.ApiProperties;
import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.dto.IncidentResponse;
import dev.josemariamerchan.controlweb.incident.dto.IncidentStatus;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/** Read access to incidents: newest first (opened_at DESC), optionally by status and by site. Read-only. */
@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id"));

	private final IncidentRepository incidents;
	private final SiteRepository sites;
	private final ApiProperties limits;

	public IncidentQueryService(IncidentRepository incidents, SiteRepository sites, ApiProperties limits) {
		this.incidents = incidents;
		this.sites = sites;
		this.limits = limits;
	}

	/**
	 * @param status "open" or "resolved"; absent means both
	 * @param siteId only the incidents of this site, if given
	 * @throws dev.josemariamerchan.controlweb.common.web.InvalidQueryException for a bad status, page or size
	 * @throws SiteNotFoundException if a siteId is given and no such site exists
	 */
	public PageResponse<IncidentResponse> list(String status, Long siteId, Integer page, Integer size) {
		IncidentStatus wanted = IncidentStatus.parse(status);
		Pageable pageable = QueryParams.page(page, size, limits, NEWEST_FIRST);
		if (siteId != null && !sites.existsById(siteId)) {
			throw new SiteNotFoundException(siteId);
		}

		Specification<Incident> filter = Specification.unrestricted();
		if (siteId != null) {
			filter = filter.and((root, query, builder) -> builder.equal(root.get("site").get("id"), siteId));
		}
		if (wanted == IncidentStatus.OPEN) {
			filter = filter.and((root, query, builder) -> builder.isNull(root.get("resolvedAt")));
		}
		else if (wanted == IncidentStatus.RESOLVED) {
			filter = filter.and((root, query, builder) -> builder.isNotNull(root.get("resolvedAt")));
		}
		return PageResponse.of(incidents.findAll(filter, pageable), IncidentResponse::from);
	}

	/** @throws IncidentNotFoundException if there is no such incident */
	public IncidentResponse get(Long id) {
		return incidents.findWithSiteById(id).map(IncidentResponse::from).orElseThrow(() -> new IncidentNotFoundException(id));
	}
}
