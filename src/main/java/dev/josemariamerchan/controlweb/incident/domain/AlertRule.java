package dev.josemariamerchan.controlweb.incident.domain;

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
 * A per-site threshold for one incident type. The meaning of {@code threshold} depends on the
 * type: consecutive failures for DOWN, milliseconds for HIGH_LATENCY, days for SSL_EXPIRING.
 */
@Entity
@Table(name = "alert_rules")
public class AlertRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "site_id", nullable = false)
	private Site site;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private IncidentType type;

	@Column(nullable = false)
	private int threshold;

	@Column(nullable = false)
	private boolean enabled = true;

	protected AlertRule() {
		// required by JPA
	}

	public AlertRule(Site site, IncidentType type, int threshold) {
		this.site = site;
		this.type = type;
		this.threshold = threshold;
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

	public int getThreshold() {
		return threshold;
	}

	public boolean isEnabled() {
		return enabled;
	}
}
