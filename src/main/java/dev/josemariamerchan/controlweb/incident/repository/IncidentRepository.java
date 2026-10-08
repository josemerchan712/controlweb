package dev.josemariamerchan.controlweb.incident.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.josemariamerchan.controlweb.incident.domain.Incident;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import jakarta.persistence.LockModeType;

public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

	/** Types of the incidents currently open for a site (what the rule engine needs to know). */
	@Query("select i.type from Incident i where i.site.id = :siteId and i.resolvedAt is null")
	List<IncidentType> findOpenTypes(@Param("siteId") Long siteId);

	/**
	 * Every open incident of every site, as light rows (one statement, no entities). Uses the partial index
	 * on open incidents.
	 */
	@Query("""
			select new dev.josemariamerchan.controlweb.incident.repository.OpenIncidentRow(i.site.id, i.type, i.openedAt)
			from Incident i
			where i.resolvedAt is null
			order by i.openedAt, i.id
			""")
	List<OpenIncidentRow> findAllOpenRows();

	/** The open incidents of just these sites, as light rows (the public page never reads other sites' incidents). */
	@Query("""
			select new dev.josemariamerchan.controlweb.incident.repository.OpenIncidentRow(i.site.id, i.type, i.openedAt)
			from Incident i
			where i.resolvedAt is null and i.site.id in :siteIds
			order by i.openedAt, i.id
			""")
	List<OpenIncidentRow> findOpenRowsForSites(@Param("siteIds") Collection<Long> siteIds);

	/** A filtered page of incidents WITH their site loaded in the same statement (no query per row). */
	@Override
	@EntityGraph(attributePaths = "site")
	Page<Incident> findAll(Specification<Incident> specification, Pageable pageable);

	@EntityGraph(attributePaths = "site")
	Optional<Incident> findWithSiteById(Long id);

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
