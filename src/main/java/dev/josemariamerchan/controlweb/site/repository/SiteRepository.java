package dev.josemariamerchan.controlweb.site.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.josemariamerchan.controlweb.site.domain.Site;

public interface SiteRepository extends JpaRepository<Site, Long> {
}
