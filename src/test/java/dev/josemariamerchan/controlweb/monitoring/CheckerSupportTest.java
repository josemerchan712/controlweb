package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import dev.josemariamerchan.controlweb.config.CheckerProperties;
import dev.josemariamerchan.controlweb.monitoring.checker.CertificateInspector;
import dev.josemariamerchan.controlweb.monitoring.checker.CheckResult;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpClientConfig;
import dev.josemariamerchan.controlweb.monitoring.checker.MessageSanitizer;

/** Small pieces of the checker: message sanitizing, certificate inspection, HTTP client and properties. */
class CheckerSupportTest {

	// ---- MessageSanitizer / CheckResult ----

	@ParameterizedTest(name = "[{index}]")
	@CsvSource(delimiter = '|', value = {
			"Failed to fetch https://example.com/path?token=abc123&x=1 now | Failed to fetch https://example.com/path now",
			"see http://example.com/a#section-with-token | see http://example.com/a",
			"login at https://admin:hunter2@example.com/panel failed | login at https://example.com/panel failed",
			"two urls https://a.test/x?k=v and http://b.test/y?k=w | two urls https://a.test/x and http://b.test/y",
			"HTTPS://EXAMPLE.COM/P?TOKEN=ABC | HTTPS://EXAMPLE.COM/P",
			"no url here | no url here"
	})
	@DisplayName("URLs lose their query string, fragment and credentials")
	void stripsSensitiveUrlParts(String input, String expected) {
		assertThat(MessageSanitizer.sanitize(input)).isEqualTo(expected);
	}

	@Test
	@DisplayName("line breaks, tabs and control characters are flattened to single spaces")
	void flattensWhitespace() {
		assertThat(MessageSanitizer.sanitize("line one\r\n\tline two\u0000\u0007 end  ")).isEqualTo("line one line two end");
	}

	@Test
	@DisplayName("messages are capped at 500 characters; shorter ones are untouched")
	void capsLength() {
		String exactly500 = "x".repeat(500);
		String sanitizedLong = MessageSanitizer.sanitize("y".repeat(5000));

		assertThat(MessageSanitizer.sanitize(exactly500)).isEqualTo(exactly500);
		assertThat(sanitizedLong).hasSize(500).endsWith("…");
		assertThat(MessageSanitizer.sanitize(null)).isNull();
	}

	@Test
	@DisplayName("the secret is removed even if truncation would have cut the URL in the middle")
	void queryRemovedBeforeTruncation() {
		String message = "z".repeat(480) + " https://example.com/p?token=SECRET123 tail";

		assertThat(MessageSanitizer.sanitize(message)).doesNotContain("SECRET123").hasSizeLessThanOrEqualTo(500);
	}

	@Test
	@DisplayName("every CheckResult stores a sanitized error message of at most 500 characters")
	void checkResultSanitizesItsMessage() {
		CheckResult result = CheckResult.failed(null, null,
				"boom https://example.com/x?token=SECRET123 " + "a".repeat(2000), null);

		assertThat(result.errorMessage()).hasSize(500).doesNotContain("SECRET123").doesNotContain("token=");
		assertThat(CheckResult.succeeded(200, 12, null).errorMessage()).isNull();
	}

	// ---- CertificateInspector ----

	@Test
	@DisplayName("reads the expiry of the server's own certificate (the first of the chain), not the CA's")
	void readsLeafExpiry() throws Exception {
		TestPki.CertificateAuthority ca = TestPki.CertificateAuthority.create("CA");
		Instant notAfter = Instant.now().plus(10, ChronoUnit.DAYS);
		TestPki.Identity leaf = ca.issue("localhost", List.of("localhost"), Instant.now().minusSeconds(60), notAfter);
		SSLSession session = mock(SSLSession.class);
		when(session.getPeerCertificates()).thenReturn(new Certificate[] { leaf.certificate(), ca.certificate });

		assertThat(new CertificateInspector().expiryOf(session))
				.contains(leaf.certificate().getNotAfter().toInstant())
				.isNotEqualTo(java.util.Optional.of(ca.certificate.getNotAfter().toInstant()));
	}

	@Test
	@DisplayName("no certificate, an unverified peer or a non-X.509 certificate give no expiry instead of an error")
	void toleratesMissingCertificates() throws Exception {
		CertificateInspector inspector = new CertificateInspector();
		SSLSession empty = mock(SSLSession.class);
		when(empty.getPeerCertificates()).thenReturn(new Certificate[0]);
		SSLSession unverified = mock(SSLSession.class);
		when(unverified.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("no peer"));
		SSLSession foreign = mock(SSLSession.class);
		when(foreign.getPeerCertificates()).thenReturn(new Certificate[] { mock(Certificate.class) });

		assertThat(inspector.expiryOf(empty)).isEmpty();
		assertThat(inspector.expiryOf(unverified)).isEmpty();
		assertThat(inspector.expiryOf(foreign)).isEmpty();
		assertThat(X509Certificate.class).isNotNull();
	}

	// ---- HttpClient configuration ----

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CheckerProperties.class)
	@Import(HttpClientConfig.class)
	static class ClientContext {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ClientContext.class);

	@Test
	@DisplayName("there is exactly one shared HttpClient: connect timeout from config, never follows redirects itself")
	void singleConfiguredClient() {
		runner.withPropertyValues("app.checker.connect-timeout=3s").run(context -> {
			assertThat(context.getBeansOfType(HttpClient.class)).hasSize(1);
			HttpClient client = context.getBean(HttpClient.class);
			assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
			assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
			assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
		});
	}

	// ---- CheckerProperties ----

	@Test
	@DisplayName("defaults: 5 s to connect, 10 s per request, 5 redirects, ControlWeb/1.0")
	void propertyDefaults() {
		runner.run(context -> {
			CheckerProperties properties = context.getBean(CheckerProperties.class);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
			assertThat(properties.maxRedirects()).isEqualTo(5);
			assertThat(properties.userAgent()).isEqualTo("ControlWeb/1.0");
			assertThat(properties.allowPrivateHosts()).isFalse();
		});
	}

	@Test
	@DisplayName("every setting can be overridden")
	void propertyOverrides() {
		runner.withPropertyValues("app.checker.connect-timeout=2s", "app.checker.request-timeout=750ms",
				"app.checker.max-redirects=3", "app.checker.user-agent=Probe/9", "app.checker.allow-private-hosts=true")
				.run(context -> {
					CheckerProperties properties = context.getBean(CheckerProperties.class);
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofMillis(750));
					assertThat(properties.maxRedirects()).isEqualTo(3);
					assertThat(properties.userAgent()).isEqualTo("Probe/9");
					assertThat(properties.allowPrivateHosts()).isTrue();
				});
	}

	@ParameterizedTest(name = "{0} is rejected")
	@ValueSource(strings = { "app.checker.connect-timeout=0s", "app.checker.request-timeout=-1s",
			"app.checker.max-redirects=-1", "app.checker.max-redirects=11", "app.checker.user-agent= " })
	void invalidPropertiesAreRejected(String property) {
		runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("the convenience constructor keeps the standard timeouts")
	void convenienceConstructor() {
		CheckerProperties properties = new CheckerProperties(false);

		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThatThrownBy(() -> new CheckerProperties(false, Duration.ZERO, Duration.ofSeconds(1), 5, "x"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
