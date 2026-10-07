package dev.josemariamerchan.controlweb.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.josemariamerchan.controlweb.monitoring.domain.Check;

public interface CheckRepository extends JpaRepository<Check, Long> {
}
