package dev.josemariamerchan.controlweb.common.error;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.josemariamerchan.controlweb.auth.service.InvalidCredentialsException;
import dev.josemariamerchan.controlweb.common.net.UnsafeHostException;
import dev.josemariamerchan.controlweb.incident.service.InvalidRuleException;
import dev.josemariamerchan.controlweb.monitoring.service.CheckInProgressException;
import dev.josemariamerchan.controlweb.site.service.DuplicateSiteException;
import dev.josemariamerchan.controlweb.site.service.InvalidSiteUrlException;
import dev.josemariamerchan.controlweb.site.service.SiteNotFoundException;

/**
 * Turns exceptions into RFC 9457 problem details ({@code application/problem+json}). Extending
 * {@link ResponseEntityExceptionHandler} already covers the standard Spring MVC errors
 * (malformed JSON, wrong parameter types, unsupported methods...).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new TreeMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		ProblemDetail problem = ex.getBody();
		problem.setDetail("Validation failed");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler(SiteNotFoundException.class)
	ProblemDetail handleNotFound(SiteNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(CheckInProgressException.class)
	ProblemDetail handleCheckInProgress(CheckInProgressException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(DuplicateSiteException.class)
	ProblemDetail handleDuplicate(DuplicateSiteException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler({ InvalidSiteUrlException.class, UnsafeHostException.class, InvalidRuleException.class })
	ProblemDetail handleBadSiteTarget(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}
}
