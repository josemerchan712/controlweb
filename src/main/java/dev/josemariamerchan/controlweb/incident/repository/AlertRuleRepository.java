package dev.josemariamerchan.controlweb.incident.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.josemariamerchan.controlweb.incident.domain.AlertRule;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
}
