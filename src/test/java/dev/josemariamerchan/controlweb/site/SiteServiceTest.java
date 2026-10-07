package dev.josemariamerchan.controlweb.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import dev.josemariamerchan.controlweb.common.net.HostSafetyValidator;
import dev.josemariamerchan.controlweb.common.net.UnsafeHostException;
import dev.josemariamerchan.controlweb.config.AlertDefaultsProperties;
import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.repository.AlertRuleRepository;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.dto.SiteRequest;
import dev.josemariamerchan.controlweb.site.dto.SiteResponse;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;
import dev.josemariamerchan.controlweb.site.service.DuplicateSiteException;
import dev.josemariamerchan.controlweb.site.service.InvalidSiteUrlException;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;
import dev.josemariamerchan.controlweb.site.service.SiteService;

@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
	private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");

	@Mock
	private SiteRepository sites;

	@Mock
	private AlertRuleRepository alertRules;

	@Mock
	private HostSafetyValidator hostSafety;

	@Captor
	private ArgumentCaptor<Site> siteCaptor;

	@Captor
	private ArgumentCaptor<List<AlertRule>> rulesCaptor;

	private SiteService service;

	@BeforeEach
	void setUp() {
		service = new SiteService(sites, alertRules, hostSafety, new AlertDefaultsProperties(2, 2000, 14),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	// ---- create ----

	@Test
	@DisplayName("create normalizes the URL, checks the host, saves the site and its 3 default rules")
	void createSavesNormalizedSiteWithDefaultRules() {
		when(sites.saveAndFlush(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SiteResponse response = service.create(new SiteRequest("Blog", "HTTPS://Blog.Example.com/#top", 60, null, null));

		verify(hostSafety).assertSafe("blog.example.com");
		verify(sites).saveAndFlush(siteCaptor.capture());
		Site saved = siteCaptor.getValue();
		assertThat(saved.getUrl()).isEqualTo("https://blog.example.com");
		assertThat(saved.getName()).isEqualTo("Blog");
		assertThat(saved.getCheckIntervalSeconds()).isEqualTo(60);
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);

		verify(alertRules).saveAll(rulesCaptor.capture());
		assertThat(rulesCaptor.getValue())
				.extracting(AlertRule::getType, AlertRule::getThreshold, AlertRule::isEnabled)
				.containsExactlyInAnyOrder(
						tuple(IncidentType.DOWN, 2, true),
						tuple(IncidentType.HIGH_LATENCY, 2000, true),
						tuple(IncidentType.SSL_EXPIRING, 14, true));
		assertThat(rulesCaptor.getValue()).allMatch(rule -> rule.getSite() == saved);

		assertThat(response.url()).isEqualTo("https://blog.example.com");
	}

	@Test
	@DisplayName("create defaults to active=true and publicVisible=false when omitted")
	void createAppliesDefaults() {
		when(sites.saveAndFlush(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SiteResponse response = service.create(new SiteRequest("Blog", "https://blog.example.com", 60, null, null));

		assertThat(response.active()).isTrue();
		assertThat(response.publicVisible()).isFalse();
	}

	@Test
	@DisplayName("create honours explicit active and publicVisible flags")
	void createHonoursFlags() {
		when(sites.saveAndFlush(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SiteResponse response = service.create(new SiteRequest("Blog", "https://blog.example.com", 60, false, true));

		assertThat(response.active()).isFalse();
		assertThat(response.publicVisible()).isTrue();
	}

	@Test
	@DisplayName("create rejects a URL that already exists once normalized")
	void createRejectsDuplicateUrl() {
		when(sites.existsByUrl("https://blog.example.com")).thenReturn(true);

		assertThatThrownBy(() -> service.create(new SiteRequest("Blog", "https://BLOG.example.com/", 60, null, null)))
				.isInstanceOf(DuplicateSiteException.class);

		verify(sites, never()).saveAndFlush(any());
		verifyNoInteractions(alertRules);
	}

	@Test
	@DisplayName("create maps a unique-constraint race at the database to a duplicate error")
	void createMapsDatabaseUniqueViolation() {
		when(sites.saveAndFlush(any(Site.class))).thenThrow(new DataIntegrityViolationException("unique"));

		assertThatThrownBy(() -> service.create(new SiteRequest("Blog", "https://blog.example.com", 60, null, null)))
				.isInstanceOf(DuplicateSiteException.class);

		verifyNoInteractions(alertRules);
	}

	@Test
	@DisplayName("create rejects URLs with embedded credentials before any host or database work")
	void createRejectsCredentials() {
		assertThatThrownBy(() -> service.create(new SiteRequest("Blog", "http://user:pass@blog.example.com", 60, null, null)))
				.isInstanceOf(InvalidSiteUrlException.class);

		verifyNoInteractions(hostSafety, sites, alertRules);
	}

	@Test
	@DisplayName("create rejects a host that resolves to a non-public address and saves nothing")
	void createRejectsUnsafeHost() {
		doThrow(new UnsafeHostException("internal.example.com")).when(hostSafety).assertSafe("internal.example.com");

		assertThatThrownBy(() -> service.create(new SiteRequest("Internal", "http://internal.example.com", 60, null, null)))
				.isInstanceOf(UnsafeHostException.class);

		verify(sites, never()).saveAndFlush(any());
		verifyNoInteractions(alertRules);
	}

	// ---- read ----

	@Test
	@DisplayName("findAll returns all sites ordered by name")
	void findAllOrderedByName() {
		when(sites.findAll(Sort.by("name").ascending())).thenReturn(List.of(
				new Site("Alpha", "https://alpha.example.com", 60, EARLIER),
				new Site("Beta", "https://beta.example.com", 120, EARLIER)));

		assertThat(service.findAll()).extracting(SiteResponse::name).containsExactly("Alpha", "Beta");
	}

	@Test
	@DisplayName("findById returns the site")
	void findByIdReturnsSite() {
		when(sites.findById(1L)).thenReturn(Optional.of(new Site("Blog", "https://blog.example.com", 60, EARLIER)));

		assertThat(service.findById(1L).name()).isEqualTo("Blog");
	}

	@Test
	@DisplayName("findById throws when the site does not exist")
	void findByIdNotFound() {
		when(sites.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findById(99L)).isInstanceOf(SiteNotFoundException.class);
	}

	// ---- update ----

	@Test
	@DisplayName("update changes every field, normalizes the URL and stamps updatedAt")
	void updateChangesFields() {
		Site existing = new Site("Blog", "https://blog.example.com", 60, EARLIER);
		when(sites.findById(1L)).thenReturn(Optional.of(existing));

		SiteResponse response = service.update(1L,
				new SiteRequest("New name", "https://NEW.example.com:443/", 300, false, true));

		verify(hostSafety).assertSafe("new.example.com");
		assertThat(existing.getName()).isEqualTo("New name");
		assertThat(existing.getUrl()).isEqualTo("https://new.example.com");
		assertThat(existing.getCheckIntervalSeconds()).isEqualTo(300);
		assertThat(existing.isActive()).isFalse();
		assertThat(existing.isPublicVisible()).isTrue();
		assertThat(existing.getCreatedAt()).isEqualTo(EARLIER);
		assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
		assertThat(response.url()).isEqualTo("https://new.example.com");
	}

	@Test
	@DisplayName("update keeps the current flags when they are omitted")
	void updateKeepsFlagsWhenOmitted() {
		Site existing = new Site("Blog", "https://blog.example.com", 60, false, true, EARLIER);
		when(sites.findById(1L)).thenReturn(Optional.of(existing));

		service.update(1L, new SiteRequest("Blog", "https://blog.example.com", 60, null, null));

		assertThat(existing.isActive()).isFalse();
		assertThat(existing.isPublicVisible()).isTrue();
	}

	@Test
	@DisplayName("update rejects a URL used by a different site")
	void updateRejectsUrlOfAnotherSite() {
		Site existing = new Site("Blog", "https://blog.example.com", 60, EARLIER);
		when(sites.findById(1L)).thenReturn(Optional.of(existing));
		when(sites.existsByUrlAndIdNot("https://shop.example.com", 1L)).thenReturn(true);

		assertThatThrownBy(() -> service.update(1L, new SiteRequest("Blog", "https://shop.example.com", 60, null, null)))
				.isInstanceOf(DuplicateSiteException.class);

		assertThat(existing.getUrl()).isEqualTo("https://blog.example.com");
	}

	@Test
	@DisplayName("update rejects a host that now resolves to a non-public address")
	void updateRejectsUnsafeHost() {
		Site existing = new Site("Blog", "https://blog.example.com", 60, EARLIER);
		when(sites.findById(1L)).thenReturn(Optional.of(existing));
		doThrow(new UnsafeHostException("internal.example.com")).when(hostSafety).assertSafe("internal.example.com");

		assertThatThrownBy(() -> service.update(1L, new SiteRequest("Blog", "http://internal.example.com", 60, null, null)))
				.isInstanceOf(UnsafeHostException.class);

		assertThat(existing.getUrl()).isEqualTo("https://blog.example.com");
	}

	@Test
	@DisplayName("update throws when the site does not exist")
	void updateNotFound() {
		when(sites.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(99L, new SiteRequest("Blog", "https://blog.example.com", 60, null, null)))
				.isInstanceOf(SiteNotFoundException.class);

		verify(sites, never()).existsByUrlAndIdNot(anyString(), anyLong());
	}

	// ---- delete ----

	@Test
	@DisplayName("delete is a single deleteById: children are removed by the database cascade")
	void deleteIsASingleDeleteById() {
		when(sites.existsById(1L)).thenReturn(true);

		service.delete(1L);

		verify(sites).deleteById(1L);
		verifyNoInteractions(alertRules);
	}

	@Test
	@DisplayName("delete throws when the site does not exist")
	void deleteNotFound() {
		when(sites.existsById(99L)).thenReturn(false);

		assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(SiteNotFoundException.class);

		verify(sites, never()).deleteById(anyLong());
	}
}
