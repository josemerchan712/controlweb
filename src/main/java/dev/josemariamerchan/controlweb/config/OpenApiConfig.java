package dev.josemariamerchan.controlweb.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * API documentation settings, kept in one place so the controllers only carry a short summary per endpoint.
 * The document is only served in the {@code dev} profile (see application.yml and SecurityConfig).
 *
 * <p>Everything that repeats is added here by rule instead of annotating every method: the Bearer scheme (required
 * everywhere except the public endpoints) and the RFC 9457 {@code ProblemDetail} error responses.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String BEARER = "bearerAuth";
	static final String PROBLEM_SCHEMA = "ProblemDetail";
	private static final String PROBLEM_JSON = "application/problem+json";

	/** Endpoints that need no token, by exact path. */
	private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/public/status");

	@Bean
	OpenAPI controlWebOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Control Web API").version("1.0")
						.description("Monitors websites (uptime, latency, SSL), keeps their history, opens and closes "
								+ "incidents and alerts through Telegram. Everything needs a Bearer JWT (obtained "
								+ "from POST /api/auth/login) except the login and GET /api/public/status. Errors "
								+ "are RFC 9457 problem details (application/problem+json)."))
				.schemaRequirement(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
						.bearerFormat("JWT").description("Access token returned by POST /api/auth/login"))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}

	@Bean
	OpenApiCustomizer problemDetailsAndPublicEndpoints() {
		return openApi -> {
			openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
			openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
					.forEach((method, operation) -> describe(path, method, operation)));
		};
	}

	private static void describe(String path, PathItem.HttpMethod method, Operation operation) {
		boolean isPublic = PUBLIC_PATHS.contains(path);
		if (isPublic) {
			operation.setSecurity(List.of()); // overrides the global Bearer requirement
		}
		boolean hasInput = operation.getRequestBody() != null
				|| (operation.getParameters() != null && !operation.getParameters().isEmpty());
		if (hasInput) {
			problem(operation, "400", "Invalid request (validation, parameter or unsafe address)");
		}
		if (!isPublic) {
			problem(operation, "401", "Missing, invalid or expired token");
		}
		if ("/api/auth/login".equals(path)) {
			problem(operation, "401", "Wrong email or password");
			problem(operation, "429", "Too many login attempts (see Retry-After)");
		}
		if (path.contains("{")) {
			problem(operation, "404", "The site or incident does not exist");
		}
		if (method == PathItem.HttpMethod.POST && path.endsWith("/check")) {
			problem(operation, "409", "A check of this site is already running");
		}
		if (method == PathItem.HttpMethod.POST && path.equals("/api/sites")
				|| method == PathItem.HttpMethod.PUT && path.equals("/api/sites/{id}")) {
			problem(operation, "409", "A site with this URL already exists");
		}
		if (path.equals("/api/alerts/test")) {
			problem(operation, "429", "Called again within the minimum interval");
			problem(operation, "503", "Telegram is not configured or did not accept the message");
		}
		if (path.equals("/api/public/status")) {
			problem(operation, "503", "No data available yet (retry after the indicated seconds)");
		}
	}

	private static void problem(Operation operation, String status, String description) {
		if (operation.getResponses().containsKey(status)) {
			return;
		}
		Content content = new Content().addMediaType(PROBLEM_JSON,
				new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)));
		operation.getResponses().addApiResponse(status, new ApiResponse().description(description).content(content));
	}

	private static Schema<?> problemSchema() {
		Schema<Object> schema = new Schema<>();
		schema.setType("object");
		schema.setDescription("RFC 9457 problem details. Never contains stack traces, SQL or secrets.");
		schema.setProperties(Map.of(
				"type", new StringSchema().example("about:blank"),
				"title", new StringSchema().example("Bad Request"),
				"status", new Schema<Integer>().type("integer").example(400),
				"detail", new StringSchema().example("The URL must start with http:// or https://"),
				"instance", new StringSchema().example("/api/sites")));
		return schema;
	}
}
