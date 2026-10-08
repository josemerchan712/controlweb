package dev.josemariamerchan.controlweb.monitoring.checker;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.josemariamerchan.controlweb.config.CheckerProperties;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

	/**
	 * The ONE client shared by every check (it owns the connection pool and its threads).
	 * Redirects are never followed automatically: {@link HttpSiteChecker} follows them by hand so
	 * every hop can be validated again.
	 */
	@Bean
	public HttpClient siteCheckHttpClient(CheckerProperties properties) {
		return newClientBuilder(properties).build();
	}

	/** Exposed so tests can build the real configuration with a custom SSL context. */
	public static HttpClient.Builder newClientBuilder(CheckerProperties properties) {
		return HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				// Plain HTTP/1.1: no h2c upgrade headers that some servers dislike.
				.version(HttpClient.Version.HTTP_1_1);
	}
}
