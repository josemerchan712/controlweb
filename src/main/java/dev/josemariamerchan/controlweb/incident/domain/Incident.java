package dev.josemariamerchan.controlweb.incident.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;
import dev.josemariamerchan.controlweb.site.domain.Site;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A detected problem on a site. It is open while {@code resolvedAt} is null; the database allows
 * at most one open incident per (site, type).
 *
 * <p>Timestamps are kept at microsecond precision, the precision of PostgreSQL's {@code timestamptz}, so
 * the values announced in events are exactly the ones stored.
 *
 * <p>{@code details} is free text for humans. It is always sanitized: at most 500 characters and no
 * query strings or credentials of URLs, because it ends up in the API and in notifications.
 */
@Entity
@Table(name = "incidents")
public class Incident {

	public static final int MAX_DETAILS_LENGTH = MessageSanitizer.MAX_LENGTH;

	private static final String RESOLUTION_SEPARATOR = " | Closed: ";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "site_id", nullable = false)
	private Site site;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private IncidentType type;

	@Column(name = "opened_at", nullable = false)
	private Instant openedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(length = MAX_DETAILS_LENGTH)
	private String details;

	protected Incident() {
		// required by JPA
	}

	private Incident(Site site, IncidentType type, Instant openedAt, String details) {
		this.site = site;
		this.type = type;
		this.openedAt = openedAt.truncatedTo(ChronoUnit.MICROS);
		this.details = MessageSanitizer.sanitize(details);
	}

	public static Incident open(Site site, IncidentType type, Instant openedAt, String details) {
		return new Incident(site, type, openedAt, details);
	}

	public void resolve(Instant resolvedAt) {
		resolve(resolvedAt, null);
	}

	/**
	 * Closes the incident and appends why to its details ("... | Closed: Site deactivated"). The note is
	 * always kept: if the whole text does not fit in 500 characters, the original details are cut instead.
	 * A resolution time before the opening time (clock skew) is clamped, so the duration is never negative.
	 */
	public void resolve(Instant resolvedAt, String note) {
		Instant at = resolvedAt.truncatedTo(ChronoUnit.MICROS);
		this.resolvedAt = at.isBefore(openedAt) ? openedAt : at;
		String cleanNote = MessageSanitizer.sanitize(note);
		if (cleanNote != null && !cleanNote.isEmpty()) {
			String suffix = RESOLUTION_SEPARATOR + cleanNote;
			String original = details == null ? "" : details;
			int room = Math.max(0, MAX_DETAILS_LENGTH - suffix.length());
			this.details = MessageSanitizer.sanitize((original.length() > room ? original.substring(0, room) : original) + suffix);
		}
	}

	public boolean isOpen() {
		return resolvedAt == null;
	}

	public Long getId() {
		return id;
	}

	public Site getSite() {
		return site;
	}

	public IncidentType getType() {
		return type;
	}

	public Instant getOpenedAt() {
		return openedAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public String getDetails() {
		return details;
	}
}
