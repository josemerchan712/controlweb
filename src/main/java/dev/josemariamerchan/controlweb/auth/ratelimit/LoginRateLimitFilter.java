package dev.josemariamerchan.controlweb.auth.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * First gate of {@code POST /api/auth/login}. It runs BEFORE the Spring Security chain and before the body is parsed, so
 * even broken requests, and requests carrying a garbage Bearer token, count against the client and cost nothing. The
 * 429 is rendered by the application's {@code @RestControllerAdvice} (same problem-detail format as every other error).
 * The per-account part of the limit needs the email and lives in {@code AuthController}.
 *
 * <p>Registered by {@link LoginRateLimitConfig} (not a scanned {@code @Component}, so MVC slice tests do not load it).
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

	static final String LOGIN_PATH = "/api/auth/login";

	private final LoginRateLimiter limiter;
	private final ClientIpResolver clients;
	private final HandlerExceptionResolver exceptionResolver;

	public LoginRateLimitFilter(LoginRateLimiter limiter, ClientIpResolver clients,
			HandlerExceptionResolver exceptionResolver) {
		this.limiter = limiter;
		this.clients = clients;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !HttpMethod.POST.matches(request.getMethod())
				|| !(request.getContextPath() + LOGIN_PATH).equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			limiter.admit(clients.clientKey(request));
		}
		catch (LoginRateLimitedException ex) {
			exceptionResolver.resolveException(request, response, null, ex);
			if (!response.isCommitted() && response.getStatus() != 429) {
				// the advice did not handle it: still answer correctly
				response.setStatus(429);
				response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
			}
			return;
		}
		chain.doFilter(request, response);
	}
}
