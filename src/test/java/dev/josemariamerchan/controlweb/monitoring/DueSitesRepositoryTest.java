package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.josemariamerchan.controlweb.PostgresDataJpaTest;
import dev.josemariamerchan.controlweb.site.domain.Site;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/** Which sites are due, decided by the {@code now} we pass in (the application's Clock), not by the database's clock. */
@PostgresDataJpaTest
class DueSitesRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	@Autowired
	private SiteRepository sites;

	private Site site(String name, int intervalSeconds, boolean active, Instant lastCheckedAt) {
		Site site = new Site(name, "https://" + name + ".example.com", intervalSeconds, active, false,
				NOW.minus(Duration.ofDays(1)));
		if (lastCheckedAt != null) {
			site.markChecked(lastCheckedAt);
		}
		return sites.saveAndFlush(site);
	}

	@Test
	@DisplayName("a site that was never checked is due")
	void neverCheckedIsDue() {
		Site fresh = site("fresh", 60, true, null);

		assertThat(sites.findDueSiteIds(NOW)).containsExactly(fresh.getId());
	}

	@Test
	@DisplayName("a site is due once its interval has elapsed (boundary included) and not before")
	void dueExactlyWhenIntervalElapsed() {
		Site overdue = site("overdue", 60, true, NOW.minusSeconds(61));
		Site exactly = site("exactly", 60, true, NOW.minusSeconds(60));
		Site notYet = site("not-yet", 60, true, NOW.minusSeconds(59));
		Site justChecked = site("just-checked", 60, true, NOW);

		assertThat(sites.findDueSiteIds(NOW)).containsExactlyInAnyOrder(overdue.getId(), exactly.getId())
				.doesNotContain(notYet.getId(), justChecked.getId());
	}

	@Test
	@DisplayName("each site uses its own interval")
	void eachSiteHasItsOwnInterval() {
		Site frequent = site("frequent", 30, true, NOW.minusSeconds(31));
		Site rare = site("rare", 3600, true, NOW.minusSeconds(31));

		assertThat(sites.findDueSiteIds(NOW)).containsExactly(frequent.getId()).doesNotContain(rare.getId());
	}

	@Test
	@DisplayName("inactive sites are never due, whether never checked or long overdue")
	void inactiveSitesAreNeverDue() {
		site("paused-new", 60, false, null);
		site("paused-old", 60, false, NOW.minus(Duration.ofDays(30)));

		assertThat(sites.findDueSiteIds(NOW)).isEmpty();
	}

	@Test
	@DisplayName("the time comes from the parameter: the same data is due later, not earlier")
	void usesTheGivenInstant() {
		Site site = site("clocked", 60, true, NOW.minusSeconds(10));

		assertThat(sites.findDueSiteIds(NOW)).isEmpty();
		assertThat(sites.findDueSiteIds(NOW.plusSeconds(49))).isEmpty();
		assertThat(sites.findDueSiteIds(NOW.plusSeconds(50))).containsExactly(site.getId());
		assertThat(sites.findDueSiteIds(NOW.minus(Duration.ofHours(1)))).isEmpty();
	}

	@Test
	@DisplayName("never-checked sites come first, then the longest overdue")
	void orderedByUrgency() {
		Site recentlyOverdue = site("recent", 60, true, NOW.minusSeconds(120));
		Site longOverdue = site("long", 60, true, NOW.minus(Duration.ofHours(5)));
		Site neverB = site("never-b", 60, true, null);
		Site neverA = site("never-a", 60, true, null);

		assertThat(sites.findDueSiteIds(NOW)).containsExactly(neverB.getId(), neverA.getId(), longOverdue.getId(),
				recentlyOverdue.getId());
	}

	@Test
	@DisplayName("nothing is due when there are no sites")
	void emptyWhenNoSites() {
		assertThat(sites.findDueSiteIds(NOW)).isEmpty();
	}
}
