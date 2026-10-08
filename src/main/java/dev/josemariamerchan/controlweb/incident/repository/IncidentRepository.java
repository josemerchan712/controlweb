package dev.josemariamerchan.controlweb.incident.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import jakarta.persistence.LockModeType;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	/** Types of the incidents currently open for a site (what the rule engine needs to know). */
	@Query("select i.type from Incident i where i.site.id = :siteId and i.resolvedAt is null")
	List<IncidentType> findOpenTypes(@Param("siteId") Long siteId);

	/**
	 * The open incident of that type, row-locked ({@code SELECT ... FOR UPDATE}). Two concurrent
	 * closers are serialized: the second one waits, then no longer finds an open incident, so an
	 * incident is closed (and announced) exactly once.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Incident i where i.site.id = :siteId and i.type = :type and i.resolvedAt is null")
	Optional<Incident> findOpenForUpdate(@Param("siteId") Long siteId, @Param("type") IncidentType type);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Incident i where i.site.id = :siteId and i.resolvedAt is null order by i.id")
	List<Incident> findAllOpenForUpdate(@Param("siteId") Long siteId);
}
