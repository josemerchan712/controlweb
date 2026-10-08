package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.site.domain.Site;

class IncidentTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
	private final Site site = new Site("Blog", "https://blog.example.com", 60, T0);

	@Test
	@DisplayName("details are capped at 500 characters")
	void detailsAreCapped() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "x".repeat(5000));

		assertThat(incident.getDetails()).hasSize(500);
	}

	@Test
	@DisplayName("details lose URL query strings, fragments and credentials")
	void detailsAreSanitized() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0,
				"Last error: GET https://blog.example.com/health?token=SECRET123#x failed; also https://user:hunter2@db.example.com/p");

		assertThat(incident.getDetails()).doesNotContain("SECRET123").doesNotContain("token=").doesNotContain("hunter2")
				.doesNotContain("user:").contains("https://blog.example.com/health");
	}

	@Test
	@DisplayName("resolving appends why it was closed")
	void resolveAppendsTheReason() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "Site is down: 2 consecutive failed checks");

		incident.resolve(T0.plusSeconds(600), "Site deactivated");

		assertThat(incident.isOpen()).isFalse();
		assertThat(incident.getResolvedAt()).isEqualTo(T0.plusSeconds(600));
		assertThat(incident.getDetails()).isEqualTo("Site is down: 2 consecutive failed checks | Closed: Site deactivated");
	}

	@Test
	@DisplayName("when the reason does not fit, the original details are cut, never the reason")
	void reasonAlwaysSurvivesTheCap() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "y".repeat(500));

		incident.resolve(T0.plusSeconds(60), "Site deactivated");

		assertThat(incident.getDetails()).hasSize(500).endsWith(" | Closed: Site deactivated");
	}

	@Test
	@DisplayName("resolving without a reason leaves the details untouched")
	void resolveWithoutReason() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "Site is down");

		incident.resolve(T0.plusSeconds(60));

		assertThat(incident.getDetails()).isEqualTo("Site is down");
		assertThat(incident.getResolvedAt()).isEqualTo(T0.plusSeconds(60));
	}

	@Test
	@DisplayName("the reason is sanitized too")
	void reasonIsSanitized() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "Site is down");

		incident.resolve(T0.plusSeconds(60), "back at https://blog.example.com/?token=SECRET123");

		assertThat(incident.getDetails()).doesNotContain("SECRET123").doesNotContain("token=");
	}

	@Test
	@DisplayName("a resolution time before the opening time is clamped, so durations are never negative")
	void resolutionTimeIsClamped() {
		Incident incident = Incident.open(site, IncidentType.DOWN, T0, "Site is down");

		incident.resolve(T0.minusSeconds(5), "clock went backwards");

		assertThat(incident.getResolvedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("timestamps are kept at microsecond precision, like the database, so events match stored rows")
	void timestampsHaveDatabasePrecision() {
		Instant nanos = Instant.parse("2026-03-01T12:00:00.123456789Z");
		Incident incident = Incident.open(site, IncidentType.DOWN, nanos, "Site is down");

		incident.resolve(nanos.plusNanos(987_654_321L));

		assertThat(incident.getOpenedAt()).isEqualTo(Instant.parse("2026-03-01T12:00:00.123456Z"));
		assertThat(incident.getResolvedAt()).isEqualTo(Instant.parse("2026-03-01T12:00:01.111111Z"));
	}

	@Test
	@DisplayName("null details are allowed")
	void nullDetails() {
		assertThat(Incident.open(site, IncidentType.DOWN, T0, null).getDetails()).isNull();
	}
}
