package dev.josemariamerchan.controlweb.incident.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.event.IncidentOpened;
import dev.josemariamerchan.controlweb.incident.event.IncidentResolved;
import dev.josemariamerchan.controlweb.incident.repository.IncidentRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Writes ONE change to the incidents table and announces it. It is a separate bean so that each call
 * gets its own short transaction: in PostgreSQL a failed statement (such as a violation of the
 * one-open-incident-per-site-and-type index) aborts the whole transaction, so every change must be
 * isolated for a failure to be handled without affecting the rest.
 *
 * <p>Events are published inside the transaction and are meant to be consumed by
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so a change that fails or rolls back is never announced.
 */
@Component
public class IncidentWriter {

	private final IncidentRepository incidents;
	private final SiteRepository sites;
	private final ApplicationEventPublisher events;

	public IncidentWriter(IncidentRepository incidents, SiteRepository sites, ApplicationEventPublisher events) {
		this.incidents = incidents;
		this.sites = sites;
		this.events = events;
	}

	/**
	 * Opens an incident in its own transaction.
	 *
	 * @throws org.springframework.dao.DataIntegrityViolationException if the site already has an open
	 *         incident of that type (another evaluation won the race): nothing is stored or announced
	 * @throws SiteNotFoundException if the site no longer exists
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void open(Long siteId, IncidentType type, String details, Instant at) {
		Site site = sites.findById(siteId).orElseThrow(() -> new SiteNotFoundException(siteId));
		// Flush now: the unique index is checked here, before anything is announced.
		Incident incident = incidents.saveAndFlush(Incident.open(site, type, at, details));
		events.publishEvent(new IncidentOpened(incident.getId(), siteId, site.getName(), type, incident.getOpenedAt(),
				incident.getDetails()));
	}

	/**
	 * Closes the open incident of that type, if any, in its own transaction. The row is locked first, so
	 * two concurrent closers produce a single close and a single event.
	 *
	 * @return true if an incident was closed by this call
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean close(Long siteId, IncidentType type, String reason, Instant at) {
		return incidents.findOpenForUpdate(siteId, type).map(incident -> {
			resolve(incident, reason, at);
			return true;
		}).orElse(false);
	}

	/**
	 * Closes every open incident of the site, joining the caller's transaction (so the incidents close
	 * atomically with whatever the caller is doing, e.g. deactivating the site).
	 *
	 * @return how many incidents were closed
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public int closeAllOpen(Long siteId, String reason, Instant at) {
		List<Incident> open = incidents.findAllOpenForUpdate(siteId);
		open.forEach(incident -> resolve(incident, reason, at));
		return open.size();
	}

	private void resolve(Incident incident, String reason, Instant at) {
		incident.resolve(at, reason);
		incidents.saveAndFlush(incident);
		Site site = incident.getSite();
		events.publishEvent(new IncidentResolved(incident.getId(), site.getId(), site.getName(), incident.getType(),
				incident.getOpenedAt(), incident.getResolvedAt(),
				Duration.between(incident.getOpenedAt(), incident.getResolvedAt()), incident.getDetails()));
	}
}
