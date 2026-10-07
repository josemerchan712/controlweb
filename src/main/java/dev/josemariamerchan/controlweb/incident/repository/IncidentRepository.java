package dev.josemariamerchan.controlweb.incident.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.josemariamerchan.controlweb.incident.domain.Incident;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
}
