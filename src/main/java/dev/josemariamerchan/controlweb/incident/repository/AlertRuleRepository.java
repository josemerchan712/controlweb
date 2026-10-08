package dev.josemariamerchan.controlweb.incident.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.josemariamerchan.controlweb.incident.domain.AlertRule;
import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

	@Query("select r from AlertRule r where r.site.id = :siteId order by r.type")
	List<AlertRule> findAllBySiteId(@Param("siteId") Long siteId);

	@Query("select r from AlertRule r where r.site.id = :siteId and r.type = :type")
	Optional<AlertRule> findBySiteIdAndType(@Param("siteId") Long siteId, @Param("type") IncidentType type);
}
