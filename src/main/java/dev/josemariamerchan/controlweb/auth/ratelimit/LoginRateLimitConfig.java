package dev.josemariamerchan.controlweb.auth.ratelimit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Registers the login rate-limit filter before Spring Security's filter chain (order -100), only for the login URL.
 * It is a registration bean in a configuration class, not a scanned {@code Filter} component, so {@code @WebMvcTest}
 * slices (which scan filters but not configuration classes) are not forced to provide the limiter.
 */
@Configuration(proxyBeanMethods = false)
class LoginRateLimitConfig {

	@Bean
	FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilter(LoginRateLimiter limiter, ClientIpResolver clients,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		FilterRegistrationBean<LoginRateLimitFilter> registration = new FilterRegistrationBean<>(
				new LoginRateLimitFilter(limiter, clients, exceptionResolver));
		registration.addUrlPatterns(LoginRateLimitFilter.LOGIN_PATH);
		registration.setOrder(-101); // Spring Security's filter chain is -100
		return registration;
	}
}
