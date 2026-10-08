package dev.josemariamerchan.controlweb.monitoring.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.josemariamerchan.controlweb.monitoring.domain.Check;

public interface CheckRepository extends JpaRepository<Check, Long> {

	/** The most recent checks of a site, newest first (ties broken by id). Pass a page request to cap how many. */
	@Query("select c from Check c where c.site.id = :siteId order by c.checkedAt desc, c.id desc")
	List<Check> findRecent(@Param("siteId") Long siteId, Pageable limit);
}
