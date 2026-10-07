package dev.josemariamerchan.controlweb.incident.domain;

import java.time.Instant;

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
 */
@Entity
@Table(name = "incidents")
public class Incident {

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

	@Column(length = 500)
	private String details;

	protected Incident() {
		// required by JPA
	}

	private Incident(Site site, IncidentType type, Instant openedAt, String details) {
		this.site = site;
		this.type = type;
		this.openedAt = openedAt;
		this.details = details;
	}

	public static Incident open(Site site, IncidentType type, Instant openedAt, String details) {
		return new Incident(site, type, openedAt, details);
	}

	public void resolve(Instant resolvedAt) {
		this.resolvedAt = resolvedAt;
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
