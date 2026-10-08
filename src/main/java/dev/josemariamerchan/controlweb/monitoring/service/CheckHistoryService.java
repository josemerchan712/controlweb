package dev.josemariamerchan.controlweb.monitoring.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.common.web.InvalidQueryException;
import dev.josemariamerchan.controlweb.common.web.PageResponse;
import dev.josemariamerchan.controlweb.common.web.QueryParams;
import dev.josemariamerchan.controlweb.config.ApiProperties;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.dto.CheckHistoryItem;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;
import jakarta.persistence.criteria.Predicate;

/** The paginated check history of one site: newest first, optionally between two instants. Read-only. */
@Service
@Transactional(readOnly = true)
public class CheckHistoryService {

	/** The order is fixed: checked_at DESC (id DESC breaks ties so pages never overlap). */
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("checkedAt"), Sort.Order.desc("id"));

	private final SiteRepository sites;
	private final CheckRepository checks;
	private final ApiProperties limits;

	public CheckHistoryService(SiteRepository sites, CheckRepository checks, ApiProperties limits) {
		this.sites = sites;
		this.checks = checks;
		this.limits = limits;
	}

	/**
	 * @param from inclusive lower bound (ISO-8601 with time zone), optional
	 * @param to   inclusive upper bound, optional
	 * @throws InvalidQueryException    for a malformed date, {@code from > to}, a range longer than the allowed
	 *                                  maximum (when both are given) or a bad page/size
	 * @throws SiteNotFoundException    if the site does not exist
	 */
	public PageResponse<CheckHistoryItem> history(Long siteId, String from, String to, Integer page, Integer size) {
		Instant start = QueryParams.instant("from", from);
		Instant end = QueryParams.instant("to", to);
		if (start != null && end != null) {
			if (start.isAfter(end)) {
				throw new InvalidQueryException("from must not be after to");
			}
			if (Duration.between(start, end).compareTo(limits.maxHistoryRange()) > 0) {
				throw new InvalidQueryException("the range between from and to cannot exceed " + describe(limits.maxHistoryRange()));
			}
		}
		Pageable pageable = QueryParams.page(page, size, limits, NEWEST_FIRST);
		if (!sites.existsById(siteId)) {
			throw new SiteNotFoundException(siteId);
		}

		Specification<Check> filter = (root, query, builder) -> {
			List<Predicate> conditions = new ArrayList<>();
			conditions.add(builder.equal(root.get("site").get("id"), siteId));
			if (start != null) {
				conditions.add(builder.greaterThanOrEqualTo(root.<Instant>get("checkedAt"), start));
			}
			if (end != null) {
				conditions.add(builder.lessThanOrEqualTo(root.<Instant>get("checkedAt"), end));
			}
			return builder.and(conditions.toArray(new Predicate[0]));
		};
		return PageResponse.of(checks.findAll(filter, pageable), CheckHistoryItem::from);
	}

	private static String describe(Duration duration) {
		return duration.toDaysPart() > 0 && duration.toHoursPart() == 0 && duration.toMinutesPart() == 0
				&& duration.toSecondsPart() == 0 ? duration.toDays() + " days" : duration.toString();
	}
}
