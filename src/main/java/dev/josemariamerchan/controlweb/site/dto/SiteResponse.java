package dev.josemariamerchan.controlweb.site.dto;

import java.time.Instant;

import dev.josemariamerchan.controlweb.site.domain.Site;

public record SiteResponse(
		Long id,
		String name,
		String url,
		int checkIntervalSeconds,
		boolean active,
		boolean publicVisible,
		Instant lastCheckedAt,
		Instant createdAt,
		Instant updatedAt) {

	public static SiteResponse from(Site site) {
		return new SiteResponse(site.getId(), site.getName(), site.getUrl(), site.getCheckIntervalSeconds(),
				site.isActive(), site.isPublicVisible(), site.getLastCheckedAt(), site.getCreatedAt(),
				site.getUpdatedAt());
	}
}
