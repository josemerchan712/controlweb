package dev.josemariamerchan.controlweb.site.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a site. {@code active} and {@code publicVisible} are optional:
 * when omitted, a new site is active and private, and an updated site keeps its current value.
 */
public record SiteRequest(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 2048) String url,
		@NotNull @Min(30) @Max(86_400) Integer checkIntervalSeconds,
		Boolean active,
		Boolean publicVisible) {
}
