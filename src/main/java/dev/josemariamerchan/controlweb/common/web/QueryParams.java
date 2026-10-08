package dev.josemariamerchan.controlweb.common.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import dev.josemariamerchan.controlweb.config.ApiProperties;

/** Parsing and validation of the query parameters shared by the read endpoints. */
public final class QueryParams {

	private QueryParams() {
	}

	/**
	 * @param page zero-based; defaults to 0
	 * @param size defaults to {@code app.api.default-page-size}; must be between 1 and {@code app.api.max-page-size}
	 * @throws InvalidQueryException when out of range
	 */
	public static Pageable page(Integer page, Integer size, ApiProperties limits, Sort sort) {
		int pageNumber = page == null ? 0 : page;
		int pageSize = size == null ? limits.defaultPageSize() : size;
		if (pageNumber < 0) {
			throw new InvalidQueryException("page must be 0 or greater");
		}
		if (pageSize < 1 || pageSize > limits.maxPageSize()) {
			throw new InvalidQueryException("size must be between 1 and " + limits.maxPageSize());
		}
		return PageRequest.of(pageNumber, pageSize, sort);
	}

	/**
	 * Parses an ISO-8601 instant ({@code 2026-03-01T12:00:00Z}) or date-time with offset
	 * ({@code 2026-03-01T13:00:00+01:00}). A blank value means "not given". A date without time is refused.
	 */
	public static Instant instant(String name, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.trim();
		try {
			return Instant.parse(text);
		}
		catch (DateTimeParseException notAnInstant) {
			try {
				return OffsetDateTime.parse(text).toInstant();
			}
			catch (DateTimeParseException notAnOffsetDateTime) {
				throw new InvalidQueryException(
						name + " must be an ISO-8601 date-time with a time zone, e.g. 2026-03-01T12:00:00Z");
			}
		}
	}
}
