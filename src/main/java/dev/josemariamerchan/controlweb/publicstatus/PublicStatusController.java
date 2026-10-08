package dev.josemariamerchan.controlweb.publicstatus;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import dev.josemariamerchan.controlweb.config.PublicProperties;

/**
 * The only endpoint that needs no token. GET only (any other method is a 405). Read the class notes of
 * {@link PublicStatusLoader} and {@link PublicSiteView} before adding anything here.
 */
@Tag(name = "Public status", description = "The only endpoint without authentication")
@RestController
@RequestMapping("/api/public/status")
public class PublicStatusController {

	private final PublicStatusCache cache;
	private final long maxAgeSeconds;

	public PublicStatusController(PublicStatusCache cache, PublicProperties properties) {
		this.cache = cache;
		this.maxAgeSeconds = properties.browserMaxAge().toSeconds();
	}

	@Operation(summary = "Public status page",
			description = "Overall state plus name, state and 30-day uptime of the sites marked as public. No URLs, no private sites. No token needed.")
	@GetMapping
	public ResponseEntity<PublicStatusResponse> status() {
		return ResponseEntity.ok()
				// Only this successful answer may be stored by browsers and CDNs. Error responses keep Spring
				// Security's no-cache headers. No cookie is ever set: the API is stateless.
				.header("Cache-Control", "public, max-age=" + maxAgeSeconds)
				.body(cache.get());
	}
}
