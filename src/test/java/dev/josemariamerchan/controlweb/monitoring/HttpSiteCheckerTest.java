package dev.josemariamerchan.controlweb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.josemariamerchan.controlweb.common.net.DnsHostResolver;
import dev.josemariamerchan.controlweb.common.net.HostResolver;
import dev.josemariamerchan.controlweb.common.net.HostSafetyValidator;
import dev.josemariamerchan.controlweb.config.CheckerProperties;
import dev.josemariamerchan.controlweb.monitoring.checker.CertificateInspector;
import dev.josemariamerchan.controlweb.monitoring.checker.CheckResult;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpClientConfig;
import dev.josemariamerchan.controlweb.monitoring.checker.HttpSiteChecker;

/** The checker against real (local) HTTP servers. TLS scenarios are in {@link HttpsSiteCheckerTest}. */
class HttpSiteCheckerTest {

	private static final String SECRET_QUERY = "?token=SECRET123&session=abc";

	private final List<TestServer> servers = new ArrayList<>();
	private final List<HttpClient> clients = new ArrayList<>();
	private ListAppender<ILoggingEvent> logs;
	private Logger checkerLogger;
	private Level previousLevel;

	@BeforeEach
	void captureLogs() {
		checkerLogger = (Logger) LoggerFactory.getLogger(HttpSiteChecker.class);
		previousLevel = checkerLogger.getLevel();
		checkerLogger.setLevel(Level.DEBUG);
		logs = new ListAppender<>();
		logs.start();
		checkerLogger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		checkerLogger.detachAppender(logs);
		checkerLogger.setLevel(previousLevel);
		servers.forEach(TestServer::close);
		clients.forEach(HttpClient::close);
	}

	// ---- helpers ----

	private TestServer server() throws IOException {
		TestServer server = TestServer.http();
		servers.add(server);
		return server;
	}

	private HttpSiteChecker checker(CheckerProperties properties, HostSafetyValidator validator) {
		HttpClient client = HttpClientConfig.newClientBuilder(properties).build();
		clients.add(client);
		return new HttpSiteChecker(client, validator, new CertificateInspector(), properties);
	}

	/** Private hosts allowed (like dev/test): the SSRF guard does not even consult DNS. */
	private HttpSiteChecker permissiveChecker() {
		CheckerProperties properties = new CheckerProperties(true);
		return checker(properties, new HostSafetyValidator(host -> {
			throw new AssertionError("DNS must not be consulted when private hosts are allowed");
		}, properties));
	}

	private HttpSiteChecker permissiveChecker(Duration connectTimeout, Duration requestTimeout, int maxRedirects) {
		CheckerProperties properties = new CheckerProperties(true, connectTimeout, requestTimeout, maxRedirects,
				"ControlWeb/1.0");
		return checker(properties, new HostSafetyValidator(host -> {
			throw new AssertionError("DNS must not be consulted when private hosts are allowed");
		}, properties));
	}

	/** Production setting (private hosts forbidden) with a simulated DNS; unknown names fall back to literals. */
	private HttpSiteChecker strictChecker(Map<String, String> dns) {
		CheckerProperties strict = new CheckerProperties(false);
		HostResolver resolver = host -> {
			String ip = dns.get(host);
			if (ip != null) {
				return new InetAddress[] { InetAddress.getByName(ip) };
			}
			return new DnsHostResolver().resolve(host); // IP literals only; real names are not used in these tests
		};
		return checker(new CheckerProperties(true), new HostSafetyValidator(resolver, strict));
	}

	private List<String> logMessages() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	// ---- basic outcomes ----

	@Test
	@DisplayName("a 200 response is up, with status, latency and no certificate (plain HTTP)")
	void okResponseIsUp() throws Exception {
		TestServer server = server();
		server.respond("/", 200);

		CheckResult result = permissiveChecker().check(server.url("/"));

		assertThat(result.up()).isTrue();
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
		assertThat(result.errorMessage()).isNull();
		assertThat(result.certExpiresAt()).isNull();
	}

	@ParameterizedTest(name = "status {0} is up")
	@ValueSource(ints = { 200, 201, 204, 304, 399 })
	void statusesBelow400AreUp(int status) throws Exception {
		TestServer server = server();
		server.respond("/", status);

		assertThat(permissiveChecker().check(server.url("/")).up()).isTrue();
	}

	@ParameterizedTest(name = "status {0} is down")
	@ValueSource(ints = { 400, 401, 403, 404, 429, 500, 502, 503 })
	void statusesFrom400AreDownButKeepTheStatusCode(int status) throws Exception {
		TestServer server = server();
		server.respond("/", status);

		CheckResult result = permissiveChecker().check(server.url("/"));

		assertThat(result.up()).isFalse();
		assertThat(result.statusCode()).isEqualTo(status);
		assertThat(result.latencyMs()).isNotNull();
		assertThat(result.errorMessage()).isEqualTo("HTTP " + status);
	}

	@Test
	@DisplayName("uses GET (not HEAD) and identifies itself with a User-Agent")
	void usesGetAndUserAgent() throws Exception {
		TestServer server = server();
		server.respond("/", 200);

		permissiveChecker().check(server.url("/"));

		assertThat(server.requests()).hasSize(1);
		assertThat(server.requests().get(0).method()).isEqualTo("GET");
		assertThat(server.requests().get(0).header("User-Agent")).isEqualTo("ControlWeb/1.0");
	}

	@Test
	@DisplayName("the User-Agent is configurable")
	void userAgentIsConfigurable() throws Exception {
		TestServer server = server();
		server.respond("/", 200);
		CheckerProperties properties = new CheckerProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(10), 5,
				"MyMonitor/2.0 (+https://example.com/bot)");

		checker(properties, new HostSafetyValidator(host -> new InetAddress[0], properties)).check(server.url("/"));

		assertThat(server.requests().get(0).header("User-Agent")).isEqualTo("MyMonitor/2.0 (+https://example.com/bot)");
	}

	@Test
	@DisplayName("latency covers the time the site takes to answer")
	void latencyIsMeasured() throws Exception {
		TestServer server = server();
		server.on("/slow", exchange -> {
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});

		CheckResult result = permissiveChecker().check(server.url("/slow"));

		assertThat(result.up()).isTrue();
		assertThat(result.latencyMs()).isBetween(190, 5000);
	}

	// ---- the body is never downloaded ----

	@Test
	@DisplayName("a site streaming an endless body is answered immediately and the transfer is dropped")
	void endlessBodyIsNotDownloaded() throws Exception {
		TestServer server = server();
		AtomicLong bytesWritten = new AtomicLong();
		AtomicBoolean clientGone = new AtomicBoolean();
		server.on("/endless", exchange -> TestServer.streamForever(exchange, bytesWritten, clientGone));
		HttpSiteChecker checker = permissiveChecker(Duration.ofSeconds(5), Duration.ofSeconds(5), 5);

		long started = System.nanoTime();
		CheckResult result = checker.check(server.url("/endless"));
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(result.up()).as("the status line alone proves the site is up").isTrue();
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(elapsedMs).as("must not wait for the body").isLessThan(2500);
		// The server notices the closed connection and stops sending: nothing close to "gigabytes" was transferred.
		long deadline = System.currentTimeMillis() + 5000;
		while (!clientGone.get() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(clientGone).as("the connection was closed instead of draining the body").isTrue();
		assertThat(bytesWritten.get()).isLessThan(64L * 1024 * 1024);
	}

	// ---- redirects ----

	@ParameterizedTest(name = "follows a {0} redirect")
	@ValueSource(ints = { 301, 302, 303, 307, 308 })
	void followsRedirects(int status) throws Exception {
		TestServer server = server();
		server.redirect("/old", status, server.url("/new"));
		server.respond("/new", 200);

		CheckResult result = permissiveChecker().check(server.url("/old"));

		assertThat(result.up()).isTrue();
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(server.requests()).extracting(TestServer.RecordedRequest::path).containsExactly("/old", "/new");
	}

	@Test
	@DisplayName("a relative Location is resolved against the current URL")
	void followsRelativeRedirect() throws Exception {
		TestServer server = server();
		server.redirect("/a/old", 302, "../b/new");
		server.respond("/b/new", 200);

		CheckResult result = permissiveChecker().check(server.url("/a/old"));

		assertThat(result.up()).isTrue();
		assertThat(server.requests()).extracting(TestServer.RecordedRequest::path).containsExactly("/a/old", "/b/new");
	}

	@Test
	@DisplayName("the verdict is the status of the FINAL response")
	void verdictIsTheFinalStatus() throws Exception {
		TestServer server = server();
		server.redirect("/old", 301, server.url("/gone"));
		server.respond("/gone", 404);

		CheckResult result = permissiveChecker().check(server.url("/old"));

		assertThat(result.up()).isFalse();
		assertThat(result.statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("exactly 5 redirects are fine")
	void fiveRedirectsAreAllowed() throws Exception {
		TestServer server = server();
		redirectChain(server, 5);

		CheckResult result = permissiveChecker().check(server.url("/r0"));

		assertThat(result.up()).isTrue();
		assertThat(server.requests()).hasSize(6);
	}

	@Test
	@DisplayName("a 6th redirect fails the check with a clear message")
	void sixRedirectsAreTooMany() throws Exception {
		TestServer server = server();
		redirectChain(server, 6);

		CheckResult result = permissiveChecker().check(server.url("/r0"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Too many redirects (max 5)");
		assertThat(server.requests()).hasSize(6); // it stopped instead of chasing the 7th
	}

	@Test
	@DisplayName("the redirect limit is configurable")
	void redirectLimitIsConfigurable() throws Exception {
		TestServer server = server();
		redirectChain(server, 2);

		CheckResult result = permissiveChecker(Duration.ofSeconds(5), Duration.ofSeconds(10), 1).check(server.url("/r0"));

		assertThat(result.errorMessage()).isEqualTo("Too many redirects (max 1)");
	}

	private static void redirectChain(TestServer server, int redirects) {
		for (int i = 0; i < redirects; i++) {
			server.redirect("/r" + i, 302, server.url("/r" + (i + 1)));
		}
		server.respond("/r" + redirects, 200);
	}

	@Test
	@DisplayName("a redirect loop (A -> B -> A) is detected and reported")
	void detectsRedirectLoop() throws Exception {
		TestServer server = server();
		server.redirect("/a", 302, server.url("/b"));
		server.redirect("/b", 302, server.url("/a"));

		CheckResult result = permissiveChecker().check(server.url("/a"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).startsWith("Redirect loop detected at ").contains("/a");
		assertThat(server.requests()).hasSize(2);
	}

	@Test
	@DisplayName("a page that redirects to itself is a loop")
	void detectsSelfRedirect() throws Exception {
		TestServer server = server();
		server.redirect("/self", 302, server.url("/self"));

		assertThat(permissiveChecker().check(server.url("/self")).errorMessage()).startsWith("Redirect loop detected");
	}

	@Test
	@DisplayName("nothing sensitive travels between hops: no cookies, credentials, referrer or origin")
	void noSensitiveHeadersBetweenHops() throws Exception {
		TestServer server = server();
		server.on("/start", exchange -> {
			exchange.getResponseHeaders().add("Set-Cookie", "sid=SECRETCOOKIE; Path=/");
			exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"private\"");
			exchange.getResponseHeaders().add("Location", server.url("/next"));
			exchange.sendResponseHeaders(302, -1);
		});
		server.respond("/next", 200);

		permissiveChecker().check(server.url("/start"));

		TestServer.RecordedRequest second = server.requests().get(1);
		assertThat(second.path()).isEqualTo("/next");
		for (String sensitive : List.of("Cookie", "Authorization", "Proxy-Authorization", "Referer", "Origin")) {
			assertThat(second.hasHeader(sensitive)).as("header %s on the redirected request", sensitive).isFalse();
		}
		assertThat(second.header("User-Agent")).isEqualTo("ControlWeb/1.0");
	}

	@Test
	@DisplayName("a redirect to a URL with credentials is refused, without echoing them")
	void redirectWithCredentialsIsRefused() throws Exception {
		TestServer server = server();
		server.redirect("/start", 302, "http://admin:hunter2@localhost:" + server.port() + "/next");
		server.respond("/next", 200);

		CheckResult result = permissiveChecker().check(server.url("/start"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Redirect target contains credentials")
				.doesNotContain("hunter2");
		assertThat(server.requests()).hasSize(1);
	}

	@ParameterizedTest(name = "a redirect to \"{0}\" is refused")
	@ValueSource(strings = { "ftp://localhost/file", "file:///etc/passwd", "javascript:alert(1)", "gopher://localhost/" })
	void redirectToOtherSchemesIsRefused(String location) throws Exception {
		TestServer server = server();
		server.redirect("/start", 302, location);

		CheckResult result = permissiveChecker().check(server.url("/start"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Redirect to an unsupported scheme");
	}

	@Test
	@DisplayName("a 3xx without Location is just a response, judged by its status")
	void redirectWithoutLocation() throws Exception {
		TestServer server = server();
		server.respond("/", 302);

		CheckResult result = permissiveChecker().check(server.url("/"));

		assertThat(result.statusCode()).isEqualTo(302);
		assertThat(result.up()).isTrue();
	}

	// ---- SSRF: validated before every request and every hop ----

	@Test
	@DisplayName("a site whose host resolves to a private address is blocked before any connection")
	void blocksPrivateHost() throws Exception {
		TestServer server = server();
		server.respond("/", 200);

		CheckResult result = strictChecker(Map.of("localhost", "10.0.0.5")).check(server.url("/"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Blocked: private address");
		assertThat(server.requests()).as("nothing was sent").isEmpty();
	}

	@Test
	@DisplayName("a redirect to a host that resolves to a private address is blocked, and never contacted")
	void blocksRedirectToPrivateHost() throws Exception {
		TestServer server = server();
		server.redirect("/start", 302, "http://internal.test:" + server.port() + "/secret");
		// "localhost" looks public to the guard (simulated), "internal.test" resolves to a private IP.
		HttpSiteChecker checker = strictChecker(Map.of("localhost", "93.184.216.34", "internal.test", "10.0.0.5"));

		CheckResult result = checker.check(server.url("/start"));

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Blocked: private address");
		assertThat(server.requests()).extracting(TestServer.RecordedRequest::path).containsExactly("/start");
	}

	@Test
	@DisplayName("a redirect to the cloud metadata address is blocked")
	void blocksRedirectToMetadataAddress() throws Exception {
		TestServer server = server();
		server.redirect("/start", 302, "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
		HttpSiteChecker checker = strictChecker(Map.of("localhost", "93.184.216.34"));

		CheckResult result = checker.check(server.url("/start"));

		assertThat(result.errorMessage()).isEqualTo("Blocked: private address");
	}

	@Test
	@DisplayName("DNS rebinding: a host that was public at one check and private at the next is blocked the second time")
	void dnsChangeBetweenChecksIsCaught() throws Exception {
		TestServer server = server();
		server.respond("/", 200);
		Map<String, String> dns = new ConcurrentHashMap<>(Map.of("localhost", "93.184.216.34"));
		HttpSiteChecker checker = strictChecker(dns);

		CheckResult first = checker.check(server.url("/"));
		dns.put("localhost", "169.254.169.254");
		CheckResult second = checker.check(server.url("/"));

		assertThat(first.up()).isTrue();
		assertThat(second.up()).isFalse();
		assertThat(second.errorMessage()).isEqualTo("Blocked: private address");
		assertThat(server.requests()).as("the second check never reached the network").hasSize(1);
	}

	// ---- failures become failed results ----

	@Test
	@DisplayName("a request timeout is reported as such, within the configured time")
	void requestTimeout() throws Exception {
		TestServer server = server();
		server.on("/hang", exchange -> {
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		HttpSiteChecker checker = permissiveChecker(Duration.ofSeconds(5), Duration.ofMillis(300), 5);

		long started = System.nanoTime();
		CheckResult result = checker.check(server.url("/hang"));
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Request timed out after 300ms");
		assertThat(result.statusCode()).isNull();
		assertThat(elapsedMs).isLessThan(2500);
	}

	@Test
	@DisplayName("a refused connection is a failed check")
	void connectionRefused() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		CheckResult result = permissiveChecker().check("http://localhost:" + closedPort + "/");

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Connection failed: refused or unreachable");
	}

	@Test
	@DisplayName("a host that does not exist is a DNS error")
	void unknownHost() {
		CheckResult result = permissiveChecker().check("http://nope.invalid/");

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("DNS error: could not resolve host nope.invalid");
	}

	@ParameterizedTest(name = "invalid URL \"{0}\" is a failed check, not an exception")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "not a url", "example.com", "ftp://example.com", "http://user:pass@example.com", "https://" })
	void invalidUrls(String url) {
		CheckResult result = permissiveChecker().check(url);

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Invalid URL");
	}

	// ---- check() never throws ----

	@Test
	@DisplayName("an unexpected runtime error inside the HTTP client becomes a failed check")
	void runtimeErrorBecomesFailedCheck() {
		HttpClient client = mock(HttpClient.class);
		when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new IllegalStateException("boom while fetching https://example.com/x" + SECRET_QUERY));
		HttpSiteChecker checker = new HttpSiteChecker(client, new HostSafetyValidator(h -> new InetAddress[0],
				new CheckerProperties(true)), new CertificateInspector(), new CheckerProperties(true));

		CheckResult result = checker.check("https://example.com/x" + SECRET_QUERY);

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Unexpected error: IllegalStateException");
		assertThat(result.errorMessage()).doesNotContain("SECRET123");
		assertThat(logMessages()).noneMatch(message -> message.contains("SECRET123"));
	}

	@Test
	@DisplayName("a failing future with an unknown exception becomes a failed check")
	void failedFutureBecomesFailedCheck() {
		HttpClient client = mock(HttpClient.class);
		when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(CompletableFuture.failedFuture(new IOException("weird failure for https://example.com/x" + SECRET_QUERY)));
		HttpSiteChecker checker = new HttpSiteChecker(client, new HostSafetyValidator(h -> new InetAddress[0],
				new CheckerProperties(true)), new CertificateInspector(), new CheckerProperties(true));

		CheckResult result = checker.check("https://example.com/x" + SECRET_QUERY);

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Request failed: IOException").doesNotContain("SECRET123");
	}

	@Test
	@DisplayName("a failure inside the SSRF guard (e.g. a broken resolver) becomes a failed check")
	void guardFailureBecomesFailedCheck() {
		CheckerProperties strict = new CheckerProperties(false);
		HostSafetyValidator brokenGuard = new HostSafetyValidator(host -> {
			throw new IllegalStateException("resolver exploded");
		}, strict);
		HttpSiteChecker checker = new HttpSiteChecker(HttpClient.newHttpClient(), brokenGuard, new CertificateInspector(),
				strict);

		CheckResult result = checker.check("https://example.com/");

		assertThat(result.up()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Unexpected error: IllegalStateException");
	}

	@Test
	@DisplayName("an interrupted check returns a failed result and keeps the thread's interrupt flag")
	void interruptionBecomesFailedCheck() throws Exception {
		TestServer server = server();
		server.on("/hang", exchange -> {
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		HttpSiteChecker checker = permissiveChecker();
		Thread.currentThread().interrupt();
		try {
			CheckResult result = checker.check(server.url("/hang"));

			assertThat(result.up()).isFalse();
			assertThat(result.errorMessage()).isEqualTo("Check interrupted");
			assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag preserved").isTrue();
		}
		finally {
			Thread.interrupted(); // clear the flag so it does not leak into other tests
		}
	}

	// ---- no query strings in messages or logs ----

	@Test
	@DisplayName("query strings (which may carry tokens) never reach error messages or logs")
	void queryStringNeverLeaks() throws Exception {
		TestServer server = server();
		server.respond("/ok", 200);
		server.respond("/error", 500);
		server.on("/hang", exchange -> {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		server.redirect("/loop", 302, server.url("/loop" + SECRET_QUERY));
		server.redirect("/to-private", 302, "http://internal.test:" + server.port() + "/x" + SECRET_QUERY);
		redirectChainWithQuery(server);
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		List<CheckResult> results = new ArrayList<>();
		HttpSiteChecker fast = permissiveChecker(Duration.ofSeconds(5), Duration.ofMillis(300), 5);
		results.add(fast.check(server.url("/ok" + SECRET_QUERY)));
		results.add(fast.check(server.url("/error" + SECRET_QUERY)));
		results.add(fast.check(server.url("/hang" + SECRET_QUERY)));
		results.add(fast.check(server.url("/loop" + SECRET_QUERY)));
		results.add(fast.check(server.url("/chain0" + SECRET_QUERY)));
		results.add(fast.check("http://localhost:" + closedPort + "/x" + SECRET_QUERY));
		results.add(fast.check("http://nope.invalid/x" + SECRET_QUERY));
		results.add(strictChecker(Map.of("localhost", "93.184.216.34", "internal.test", "10.0.0.5"))
				.check(server.url("/to-private" + SECRET_QUERY)));

		assertThat(results).hasSize(8);
		assertThat(results).extracting(CheckResult::errorMessage).filteredOn(m -> m != null)
				.allSatisfy(message -> assertThat(message).doesNotContain("SECRET123").doesNotContain("token=")
						.doesNotContain("session=").doesNotContain("?"));
		assertThat(logMessages()).isNotEmpty().allSatisfy(message -> assertThat(message).doesNotContain("SECRET123")
				.doesNotContain("token=").doesNotContain("session=").doesNotContain("?"));
	}

	private static void redirectChainWithQuery(TestServer server) {
		for (int i = 0; i < 7; i++) {
			server.redirect("/chain" + i, 302, server.url("/chain" + (i + 1) + SECRET_QUERY));
		}
	}
}
