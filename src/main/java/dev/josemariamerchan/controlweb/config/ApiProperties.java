package dev.josemariamerchan.controlweb.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of the read API.
 *
 * @param defaultPageSize  page size when the request does not give one
 * @param maxPageSize      largest page size accepted; bigger values are a 400, not silently reduced
 * @param maxHistoryRange  longest {@code to - from} accepted by the check history when both are given
 */
@ConfigurationProperties("app.api")
public record ApiProperties(
		@DefaultValue("50") int defaultPageSize,
		@DefaultValue("100") int maxPageSize,
		@DefaultValue("90d") Duration maxHistoryRange) {

	public ApiProperties {
		if (maxPageSize < 1 || maxPageSize > 1000) {
			throw new IllegalArgumentException("app.api.max-page-size must be between 1 and 1000");
		}
		if (defaultPageSize < 1 || defaultPageSize > maxPageSize) {
			throw new IllegalArgumentException("app.api.default-page-size must be between 1 and max-page-size");
		}
		if (maxHistoryRange == null || maxHistoryRange.isZero() || maxHistoryRange.isNegative()) {
			throw new IllegalArgumentException("app.api.max-history-range must be positive");
		}
	}
}
