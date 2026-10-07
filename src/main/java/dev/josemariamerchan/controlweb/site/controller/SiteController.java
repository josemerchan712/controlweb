package dev.josemariamerchan.controlweb.site.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.josemariamerchan.controlweb.site.dto.SiteRequest;
import dev.josemariamerchan.controlweb.site.dto.SiteResponse;
import dev.josemariamerchan.controlweb.site.service.SiteService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

	private final SiteService service;

	public SiteController(SiteService service) {
		this.service = service;
	}

	@GetMapping
	public List<SiteResponse> list() {
		return service.findAll();
	}

	@GetMapping("/{id}")
	public SiteResponse get(@PathVariable Long id) {
		return service.findById(id);
	}

	@PostMapping
	public ResponseEntity<SiteResponse> create(@Valid @RequestBody SiteRequest request) {
		SiteResponse created = service.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
				.buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PutMapping("/{id}")
	public SiteResponse update(@PathVariable Long id, @Valid @RequestBody SiteRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
