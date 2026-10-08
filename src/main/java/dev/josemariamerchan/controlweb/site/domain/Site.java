package dev.josemariamerchan.controlweb.site.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A website being monitored. */
@Entity
@Table(name = "sites")
public class Site {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 2048, unique = true)
	private String url;

	@Column(name = "check_interval_seconds", nullable = false)
	private int checkIntervalSeconds;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "public_visible", nullable = false)
	private boolean publicVisible = false;

	@Column(name = "last_checked_at")
	private Instant lastCheckedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Site() {
		// required by JPA
	}

	/** A new active, private site. */
	public Site(String name, String url, int checkIntervalSeconds, Instant now) {
		this(name, url, checkIntervalSeconds, true, false, now);
	}

	public Site(String name, String url, int checkIntervalSeconds, boolean active, boolean publicVisible,
			Instant now) {
		this.name = name;
		this.url = url;
		this.checkIntervalSeconds = checkIntervalSeconds;
		this.active = active;
		this.publicVisible = publicVisible;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Replaces the user-editable settings. */
	public void update(String name, String url, int checkIntervalSeconds, boolean active, boolean publicVisible,
			Instant now) {
		this.name = name;
		this.url = url;
		this.checkIntervalSeconds = checkIntervalSeconds;
		this.active = active;
		this.publicVisible = publicVisible;
		this.updatedAt = now;
	}

	/** Records that a check of this site just ran, whatever its outcome. */
	public void markChecked(Instant checkedAt) {
		this.lastCheckedAt = checkedAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}

	public int getCheckIntervalSeconds() {
		return checkIntervalSeconds;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isPublicVisible() {
		return publicVisible;
	}

	public Instant getLastCheckedAt() {
		return lastCheckedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
