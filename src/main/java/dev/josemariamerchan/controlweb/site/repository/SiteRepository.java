package dev.josemariamerchan.controlweb.site.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.josemariamerchan.controlweb.site.domain.Site;

public interface SiteRepository extends JpaRepository<Site, Long> {

	boolean existsByUrl(String url);

	boolean existsByUrlAndIdNot(String url, Long id);

	/** Only the sites meant for the PUBLIC status page: shown publicly AND being monitored. Nothing else is read. */
	List<Site> findByPublicVisibleTrueAndActiveTrue(Sort sort);

	/**
	 * Ids of the active sites whose next check is due at {@code now}: never checked, or checked at least
	 * {@code check_interval_seconds} ago. Never-checked sites come first, then the longest overdue.
	 * {@code now} is a parameter (not the database clock) so the application's injectable Clock decides.
	 */
	@Query(value = """
			select s.id
			from sites s
			where s.active = true
			  and (s.last_checked_at is null
			       or s.last_checked_at + s.check_interval_seconds * interval '1 second' <= :now)
			order by s.last_checked_at asc nulls first, s.id
			""", nativeQuery = true)
	List<Long> findDueSiteIds(@Param("now") Instant now);
}
