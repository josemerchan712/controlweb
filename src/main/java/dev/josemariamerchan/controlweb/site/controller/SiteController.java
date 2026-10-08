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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import dev.josemariamerchan.controlweb.site.dto.SiteRequest;
import dev.josemariamerchan.controlweb.site.dto.SiteResponse;
import dev.josemariamerchan.controlweb.site.service.SiteService;
import jakarta.validation.Valid;

@Tag(name = "Sites", description = "Monitored websites")
@RestController
@RequestMapping("/api/sites")
public class SiteController {

	private final SiteService service;

	public SiteController(SiteService service) {
		this.service = service;
	}

	@Operation(summary = "List sites",
			description = "Returns all monitored sites.")
	@GetMapping
	public List<SiteResponse> list() {
		return service.findAll();
	}

	@Operation(summary = "Get a site",
			description = "Returns one site by id.")
	@GetMapping("/{id}")
	public SiteResponse get(@PathVariable Long id) {
		return service.findById(id);
	}

	@Operation(summary = "Create a site",
			description = "Registers a website and creates its three default alert rules. The URL is normalized and must resolve to a public address.")
	@PostMapping
	public ResponseEntity<SiteResponse> create(@Valid @RequestBody SiteRequest request) {
		SiteResponse created = service.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
				.buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "Update a site",
			description = "Replaces the editable fields of a site, including whether it is active and whether it appears on the public status page.")
	@PutMapping("/{id}")
	public SiteResponse update(@PathVariable Long id, @Valid @RequestBody SiteRequest request) {
		return service.update(id, request);
	}

	@Operation(summary = "Delete a site",
			description = "Deletes a site together with its checks, rules and incidents.")
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
