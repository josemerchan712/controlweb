package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.common.net.HostSafetyValidator;
import dev.josemariamerchan.controlweb.config.CheckerProperties;
import dev.josemariamerchan.controlweb.monitoring.checker.CertificateInspector;
import dev.josemariamerchan.controlweb.monitoring.checker.CheckResult;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpClientConfig;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpSiteChecker;

/**
 * TLS behaviour against real HTTPS servers whose certificates are generated in memory by
 * {@link TestPki}: nothing with a private key lives in the repository.
 */
class HttpsSiteCheckerTest {

	private static TestPki.CertificateAuthority trustedCa;
	private static TestPki.CertificateAuthority unknownCa;

	private final List<TestServer> servers = new ArrayList<>();
	private final List<HttpClient> clients = new ArrayList<>();

	@BeforeAll
	static void createAuthorities() throws Exception {
		trustedCa = TestPki.CertificateAuthority.create("Test CA (trusted by the checker)");
		unknownCa = TestPki.CertificateAuthority.create("Unknown CA (not trusted)");
	}

	@AfterEach
	void tearDown() {
		servers.forEach(TestServer::close);
		clients.forEach(HttpClient::close);
	}

	private TestServer httpsServer(TestPki.Identity identity, int status) throws Exception {
		TestServer server = TestServer.https(TestPki.serverContext(identity));
		server.respond("/", status);
		servers.add(server);
		return server;
	}

	/** A checker whose HTTP client trusts only {@code trustedCa} (hostname verification stays on). */
	private HttpSiteChecker checkerTrustingTestCa() throws Exception {
		CheckerProperties properties = new CheckerProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(5), 5,
				"ControlWeb/1.0");
		SSLContext trust = TestPki.clientTrusting(trustedCa.certificate);
		HttpClient client = HttpClientConfig.newClientBuilder(properties).sslContext(trust).build();
		clients.add(client);
		return new HttpSiteChecker(client, new HostSafetyValidator(host -> new InetAddress[0], properties),
				new CertificateInspector(), properties);
	}

	private static TestPki.Identity validFor(TestPki.CertificateAuthority ca, List<String> names, Instant notBefore,
			Instant notAfter) throws Exception {
		return ca.issue("localhost", names, notBefore, notAfter);
	}

	private static void assertCleanSslMessage(CheckResult result) {
		assertThat(result.up()).isFalse();
		assertThat(result.statusCode()).isNull();
		assertThat(result.certExpiresAt()).isNull();
		assertThat(result.errorMessage()).startsWith("SSL error: ")
				.doesNotContain("\n")
				.doesNotContain("\tat ")
				.doesNotContain("javax.net")
				.doesNotContain("sun.security")
				.doesNotContain("Exception")
				.hasSizeLessThan(200);
	}

	@Test
	@DisplayName("a valid certificate: the check is up and the certificate expiry is recorded")
	void validCertificateRecordsExpiry() throws Exception {
		Instant notAfter = Instant.now().plus(45, ChronoUnit.DAYS);
		TestPki.Identity identity = validFor(trustedCa, List.of("localhost"), Instant.now().minusSeconds(3600), notAfter);
		TestServer server = httpsServer(identity, 200);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertThat(result.up()).isTrue();
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.certExpiresAt()).isEqualTo(identity.certificate().getNotAfter().toInstant());
		assertThat(result.certExpiresAt()).isBetween(notAfter.minusSeconds(2), notAfter.plusSeconds(2));
	}

	@Test
	@DisplayName("the certificate expiry is still recorded when the site answers with an error status")
	void certificateExpiryRecordedEvenWhenSiteReturns503() throws Exception {
		TestPki.Identity identity = validFor(trustedCa, List.of("localhost"), Instant.now().minusSeconds(3600),
				Instant.now().plus(20, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 503);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertThat(result.up()).isFalse();
		assertThat(result.statusCode()).isEqualTo(503);
		assertThat(result.errorMessage()).isEqualTo("HTTP 503");
		assertThat(result.certExpiresAt()).isEqualTo(identity.certificate().getNotAfter().toInstant());
	}

	@Test
	@DisplayName("an expired certificate fails the check with a clear SSL error and no stack trace")
	void expiredCertificate() throws Exception {
		TestPki.Identity identity = validFor(trustedCa, List.of("localhost"), Instant.now().minus(30, ChronoUnit.DAYS),
				Instant.now().minus(1, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 200);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertCleanSslMessage(result);
		assertThat(result.errorMessage()).isEqualTo("SSL error: the certificate has expired");
		assertThat(server.requests()).as("no HTTP request is sent over a failed handshake").isEmpty();
	}

	@Test
	@DisplayName("a certificate that is not valid yet fails the check")
	void notYetValidCertificate() throws Exception {
		TestPki.Identity identity = validFor(trustedCa, List.of("localhost"), Instant.now().plus(2, ChronoUnit.DAYS),
				Instant.now().plus(30, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 200);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertCleanSslMessage(result);
		assertThat(result.errorMessage()).isEqualTo("SSL error: the certificate is not valid yet");
	}

	@Test
	@DisplayName("a certificate issued for another host name fails the check")
	void wrongHostCertificate() throws Exception {
		TestPki.Identity identity = validFor(trustedCa, List.of("some-other-site.example"),
				Instant.now().minusSeconds(3600), Instant.now().plus(30, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 200);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertCleanSslMessage(result);
		assertThat(result.errorMessage()).isEqualTo("SSL error: the certificate does not match the host name");
	}

	@Test
	@DisplayName("a certificate signed by an unknown CA is not trusted")
	void untrustedCertificate() throws Exception {
		TestPki.Identity identity = validFor(unknownCa, List.of("localhost"), Instant.now().minusSeconds(3600),
				Instant.now().plus(30, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 200);

		CheckResult result = checkerTrustingTestCa().check(server.url("/"));

		assertCleanSslMessage(result);
		assertThat(result.errorMessage()).isEqualTo("SSL error: the certificate is not trusted");
	}

	@Test
	@DisplayName("with the JVM's default trust store, a certificate from a private CA is not trusted either")
	void defaultTrustStoreRejectsPrivateCa() throws Exception {
		TestPki.Identity identity = validFor(trustedCa, List.of("localhost"), Instant.now().minusSeconds(3600),
				Instant.now().plus(30, ChronoUnit.DAYS));
		TestServer server = httpsServer(identity, 200);
		CheckerProperties properties = new CheckerProperties(true);
		HttpClient client = HttpClientConfig.newClientBuilder(properties).build(); // default trust store
		clients.add(client);
		HttpSiteChecker checker = new HttpSiteChecker(client, new HostSafetyValidator(host -> new InetAddress[0], properties),
				new CertificateInspector(), properties);

		CheckResult result = checker.check(server.url("/"));

		assertCleanSslMessage(result);
		assertThat(result.errorMessage()).isEqualTo("SSL error: the certificate is not trusted");
	}
}
