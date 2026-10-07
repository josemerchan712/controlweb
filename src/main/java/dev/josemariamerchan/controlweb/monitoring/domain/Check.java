package dev.josemariamerchan.controlweb.monitoring.domain;

import java.time.Instant;

import dev.josemariamerchan.controlweb.site.domain.Site;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The result of one HTTP check against a site. */
@Entity
@Table(name = "checks")
public class Check {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "site_id", nullable = false)
	private Site site;

	@Column(name = "checked_at", nullable = false)
	private Instant checkedAt;

	@Column(nullable = false)
	private boolean up;

	@Column(name = "status_code")
	private Integer statusCode;

	@Column(name = "latency_ms")
	private Integer latencyMs;

	@Column(name = "error_message", length = 500)
	private String errorMessage;

	@Column(name = "cert_expires_at")
	private Instant certExpiresAt;

	protected Check() {
		// required by JPA
	}

	private Check(Site site, Instant checkedAt, boolean up, Integer statusCode, Integer latencyMs,
			String errorMessage, Instant certExpiresAt) {
		this.site = site;
		this.checkedAt = checkedAt;
		this.up = up;
		this.statusCode = statusCode;
		this.latencyMs = latencyMs;
		this.errorMessage = errorMessage;
		this.certExpiresAt = certExpiresAt;
	}

	public static Check up(Site site, Instant checkedAt, int statusCode, int latencyMs, Instant certExpiresAt) {
		return new Check(site, checkedAt, true, statusCode, latencyMs, null, certExpiresAt);
	}

	public static Check down(Site site, Instant checkedAt, Integer statusCode, Integer latencyMs, String errorMessage) {
		return new Check(site, checkedAt, false, statusCode, latencyMs, errorMessage, null);
	}

	public Long getId() {
		return id;
	}

	public Site getSite() {
		return site;
	}

	public Instant getCheckedAt() {
		return checkedAt;
	}

	public boolean isUp() {
		return up;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public Integer getLatencyMs() {
		return latencyMs;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public Instant getCertExpiresAt() {
		return certExpiresAt;
	}
}
