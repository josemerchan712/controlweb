package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import dev.josemariamerchan.controlweb.common.web.InvalidQueryException;
import dev.josemariamerchan.controlweb.common.web.QueryParams;
import dev.josemariamerchan.controlweb.config.ApiProperties;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.dto.IncidentStatus;
import dev.josemariamerchan.controlweb.monitoring.domain.Check;
import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;
import dev.josemariamerchan.controlweb.monitoring.dto.LastCheckView;
import dev.josemariamerchan.controlweb.monitoring.repository.CheckRepository;
import dev.josemariamerchan.controlweb.monitoring.service.CheckHistoryService;
import dev.josemariamerchan.controlweb.monitoring.service.SiteStateCalculator;
import dev.josemariamerchan.controlweb.monitoring.service.StatsPeriod;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/** The rules of the read API that need no database: derived state, parameters, periods and validation. */
class ReadApiUnitTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
	private static final LastCheckView UP = new LastCheckView(true, 200, 100, T0, null);
	private static final LastCheckView FAILED = new LastCheckView(false, 503, 90, T0, null);

	// ---- derived state ----

	private static Set<IncidentType> open(IncidentType... types) {
		return types.length == 0 ? EnumSet.noneOf(IncidentType.class) : EnumSet.copyOf(java.util.List.of(types));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			inactive site                                    | false | NONE   |                          | PAUSED
			inactive wins over a failed last check           | false | FAILED | DOWN                     | PAUSED
			inactive wins over everything                    | false | UP     | DOWN,HIGH_LATENCY        | PAUSED
			inactive and never checked is paused, not pending| false | NONE   |                          | PAUSED
			active, never checked                            | true  | NONE   |                          | PENDING
			healthy                                          | true  | UP     |                          | UP
			open DOWN incident, last check ok                | true  | UP     | DOWN                     | DOWN
			last check failed, no incident yet               | true  | FAILED |                          | DOWN
			failed check AND open DOWN incident              | true  | FAILED | DOWN                     | DOWN
			open HIGH_LATENCY while up                       | true  | UP     | HIGH_LATENCY             | DEGRADED
			open SSL_EXPIRING while up                       | true  | UP     | SSL_EXPIRING             | DEGRADED
			both degraded incidents while up                 | true  | UP     | HIGH_LATENCY,SSL_EXPIRING| DEGRADED
			DOWN wins over degraded incidents                | true  | UP     | DOWN,SSL_EXPIRING        | DOWN
			a failed check wins over degraded incidents      | true  | FAILED | HIGH_LATENCY             | DOWN
			""")
	void derivedState(String name, boolean active, String lastCheck, String incidents, SiteState expected) {
		LastCheckView check = switch (lastCheck) {
			case "UP" -> UP;
			case "FAILED" -> FAILED;
			default -> null;
		};
		Set<IncidentType> types = incidents == null || incidents.isBlank() ? open()
				: EnumSet.copyOf(java.util.Arrays.stream(incidents.split(",")).map(IncidentType::valueOf).toList());

		assertThat(SiteStateCalculator.derive(active, check, types)).isEqualTo(expected);
	}

	// ---- stats periods ----

	@ParameterizedTest(name = "\"{0}\" -> {1}")
	@CsvSource({ "24h,24h", "7d,7d", "30d,30d", "24H,24h", " 7D ,7d" })
	void validPeriods(String input, String label) {
		assertThat(StatsPeriod.parse(input).label()).isEqualTo(label);
	}

	@Test
	@DisplayName("the periods last exactly 24 hours, 7 days and 30 days; absent or blank means 24h")
	void periodDurationsAndDefault() {
		assertThat(StatsPeriod.parse("24h").duration()).isEqualTo(Duration.ofHours(24));
		assertThat(StatsPeriod.parse("7d").duration()).isEqualTo(Duration.ofDays(7));
		assertThat(StatsPeriod.parse("30d").duration()).isEqualTo(Duration.ofDays(30));
		assertThat(StatsPeriod.parse(null)).isEqualTo(StatsPeriod.LAST_24_HOURS);
		assertThat(StatsPeriod.parse("  ")).isEqualTo(StatsPeriod.LAST_24_HOURS);
	}

	@ParameterizedTest(name = "period \"{0}\" is rejected")
	@ValueSource(strings = { "1h", "24", "90d", "7days", "abc", "-7d", "24h;drop", "1d", "0d" })
	void invalidPeriods(String input) {
		assertThatThrownBy(() -> StatsPeriod.parse(input)).isInstanceOf(InvalidQueryException.class)
				.hasMessage("period must be one of 24h, 7d, 30d");
	}

	// ---- instants ----

	@ParameterizedTest(name = "{0} is accepted")
	@CsvSource({ "2026-03-01T12:00:00Z,2026-03-01T12:00:00Z", "2026-03-01T13:00:00+01:00,2026-03-01T12:00:00Z",
			"2026-03-01T12:00:00.123Z,2026-03-01T12:00:00.123Z", "2026-03-01T07:00:00-05:00,2026-03-01T12:00:00Z" })
	void validInstants(String input, String expected) {
		assertThat(QueryParams.instant("from", input)).isEqualTo(Instant.parse(expected));
	}

	@Test
	@DisplayName("blank means 'not given'")
	void blankInstant() {
		assertThat(QueryParams.instant("from", null)).isNull();
		assertThat(QueryParams.instant("from", "  ")).isNull();
	}

	@ParameterizedTest(name = "\"{0}\" is rejected")
	@ValueSource(strings = { "2026-03-01", "yesterday", "2026-13-45T00:00:00Z", "1700000000", "2026-03-01 12:00:00",
			"2026-03-01T12:00:00", "12:00", "'; drop table checks;--" })
	void invalidInstants(String input) {
		assertThatThrownBy(() -> QueryParams.instant("from", input)).isInstanceOf(InvalidQueryException.class)
				.hasMessageContaining("from must be an ISO-8601 date-time with a time zone");
	}

	// ---- paging parameters ----

	private final ApiProperties defaults = new ApiProperties(50, 100, Duration.ofDays(90));

	@Test
	@DisplayName("defaults: page 0, size 50; the sort is whatever the endpoint fixes")
	void pageDefaults() {
		Pageable pageable = QueryParams.page(null, null, defaults, Sort.by("x"));

		assertThat(pageable.getPageNumber()).isZero();
		assertThat(pageable.getPageSize()).isEqualTo(50);
		assertThat(pageable.getSort()).isEqualTo(Sort.by("x"));
	}

	@Test
	@DisplayName("the largest allowed size is accepted, one more is a 400 (not silently reduced)")
	void pageSizeLimit() {
		assertThat(QueryParams.page(0, 100, defaults, Sort.unsorted()).getPageSize()).isEqualTo(100);
		assertThatThrownBy(() -> QueryParams.page(0, 101, defaults, Sort.unsorted()))
				.isInstanceOf(InvalidQueryException.class).hasMessage("size must be between 1 and 100");
	}

	@Test
	@DisplayName("the maximum size is configurable")
	void pageSizeLimitIsConfigurable() {
		ApiProperties small = new ApiProperties(10, 20, Duration.ofDays(90));

		assertThat(QueryParams.page(null, null, small, Sort.unsorted()).getPageSize()).isEqualTo(10);
		assertThat(QueryParams.page(0, 20, small, Sort.unsorted()).getPageSize()).isEqualTo(20);
		assertThatThrownBy(() -> QueryParams.page(0, 21, small, Sort.unsorted())).hasMessage("size must be between 1 and 20");
	}

	@ParameterizedTest(name = "page={0} size={1} is rejected")
	@CsvSource({ "-1,10", "0,0", "0,-5", "0,101", "0,1000000" })
	void invalidPaging(int page, int size) {
		assertThatThrownBy(() -> QueryParams.page(page, size, defaults, Sort.unsorted())).isInstanceOf(InvalidQueryException.class);
	}

	@Test
	@DisplayName("ApiProperties refuses nonsense limits")
	void apiPropertiesValidation() {
		assertThatThrownBy(() -> new ApiProperties(0, 100, Duration.ofDays(90))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApiProperties(101, 100, Duration.ofDays(90))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApiProperties(50, 0, Duration.ofDays(90))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApiProperties(50, 100, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- incident status ----

	@Test
	@DisplayName("status accepts open/resolved in any case; absent means no filter; anything else is a 400")
	void incidentStatus() {
		assertThat(IncidentStatus.parse("open")).isEqualTo(IncidentStatus.OPEN);
		assertThat(IncidentStatus.parse("RESOLVED")).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(IncidentStatus.parse(null)).isNull();
		assertThat(IncidentStatus.parse(" ")).isNull();
		assertThatThrownBy(() -> IncidentStatus.parse("closed")).isInstanceOf(InvalidQueryException.class)
				.hasMessage("status must be open or resolved");
	}

	// ---- history validation (no database: the repositories are mocks) ----

	private final SiteRepository sites = mock(SiteRepository.class);
	private final CheckRepository checks = mock(CheckRepository.class);

	@SuppressWarnings("unchecked")
	private CheckHistoryService historyService(ApiProperties limits) {
		when(sites.existsById(1L)).thenReturn(true);
		when(checks.findAll(any(Specification.class), any(Pageable.class))).thenReturn((Page<Check>) new PageImpl<Check>(java.util.List.of()));
		return new CheckHistoryService(sites, checks, limits);
	}

	@Test
	@DisplayName("history: the order is fixed to checked_at DESC (then id DESC) and the default size is 50")
	void historyOrderIsFixed() {
		CheckHistoryService service = historyService(defaults);

		service.history(1L, null, null, null, null);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(checks).findAll(any(Specification.class), pageable.capture());
		assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
		assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("checkedAt"), Sort.Order.desc("id")));
	}

	@Test
	@DisplayName("history: from after to is a 400; equal bounds are fine")
	void historyFromAfterTo() {
		CheckHistoryService service = historyService(defaults);

		assertThatThrownBy(() -> service.history(1L, "2026-03-02T00:00:00Z", "2026-03-01T00:00:00Z", null, null))
				.isInstanceOf(InvalidQueryException.class).hasMessage("from must not be after to");
		assertThatCode(() -> service.history(1L, "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z", null, null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("history: exactly 90 days is accepted, one second more is a 400 that says the limit")
	void historyRangeLimit() {
		CheckHistoryService service = historyService(defaults);

		assertThatCode(() -> service.history(1L, "2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z", null, null))
				.as("2026-01-01 to 2026-04-01 is exactly 90 days").doesNotThrowAnyException();
		assertThatThrownBy(() -> service.history(1L, "2026-01-01T00:00:00Z", "2026-04-01T00:00:01Z", null, null))
				.isInstanceOf(InvalidQueryException.class)
				.hasMessage("the range between from and to cannot exceed 90 days");
	}

	@Test
	@DisplayName("history: the range limit is configurable and only applies when both bounds are given")
	void historyRangeLimitIsConfigurable() {
		CheckHistoryService service = historyService(new ApiProperties(50, 100, Duration.ofDays(2)));

		assertThatThrownBy(() -> service.history(1L, "2026-03-01T00:00:00Z", "2026-03-03T00:00:01Z", null, null))
				.hasMessage("the range between from and to cannot exceed 2 days");
		assertThatCode(() -> service.history(1L, "2020-01-01T00:00:00Z", null, null, null)).doesNotThrowAnyException();
		assertThatCode(() -> service.history(1L, null, "2020-01-01T00:00:00Z", null, null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("history: bad parameters are rejected BEFORE touching the database")
	void historyValidatesFirst() {
		CheckHistoryService service = new CheckHistoryService(sites, checks, defaults);

		assertThatThrownBy(() -> service.history(1L, "nonsense", null, null, null)).isInstanceOf(InvalidQueryException.class);
		assertThatThrownBy(() -> service.history(1L, null, null, -1, null)).isInstanceOf(InvalidQueryException.class);
		assertThatThrownBy(() -> service.history(1L, null, null, null, 101)).isInstanceOf(InvalidQueryException.class);

		verifyNoInteractions(sites, checks);
	}

	@Test
	@DisplayName("history: an unknown site is NotFound")
	void historyUnknownSite() {
		when(sites.existsById(99L)).thenReturn(false);

		assertThatThrownBy(() -> new CheckHistoryService(sites, checks, defaults).history(99L, null, null, null, null))
				.isInstanceOf(SiteNotFoundException.class);
	}
}
