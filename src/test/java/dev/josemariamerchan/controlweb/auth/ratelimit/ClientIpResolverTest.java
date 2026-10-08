package dev.josemariamerchan.controlweb.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import dev.josemariamerchan.controlweb.config.ProxyProperties;

/**
 * Which client a request belongs to. X-Forwarded-For is a header anyone can send, so it is only believed when the TCP
 * peer is exactly a configured trusted proxy (Caddy); otherwise it is ignored.
 */
class ClientIpResolverTest {

	private static final String CADDY = "10.77.0.10";

	private final ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(List.of(CADDY)));

	private static MockHttpServletRequest request(String peer, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
		request.setRemoteAddr(peer);
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

	@Test
	@DisplayName("without a trusted proxy the TCP peer is the client")
	void peerIsTheClient() {
		assertThat(resolver.clientKey(request("203.0.113.9", null))).isEqualTo("203.0.113.9");
	}

	@Test
	@DisplayName("X-Forwarded-For from an UNTRUSTED peer is ignored (it cannot be used to pick a bucket)")
	void untrustedPeerCannotForge() {
		assertThat(resolver.clientKey(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
		assertThat(resolver.clientKey(request("10.77.0.11", "1.2.3.4"))).isEqualTo("10.77.0.11");
	}

	@Test
	@DisplayName("from the trusted proxy, the client is the value Caddy wrote")
	void trustedPeerIsBelieved() {
		assertThat(resolver.clientKey(request(CADDY, "198.51.100.7"))).isEqualTo("198.51.100.7");
	}

	@Test
	@DisplayName("when the header has several entries only the RIGHTMOST one (added by our proxy) counts")
	void rightmostEntryCounts() {
		assertThat(resolver.clientKey(request(CADDY, "6.6.6.6, 7.7.7.7, 198.51.100.7"))).isEqualTo("198.51.100.7");
		assertThat(resolver.clientKey(request(CADDY, "6.6.6.6,198.51.100.7 "))).isEqualTo("198.51.100.7");
	}

	@Test
	@DisplayName("a trusted proxy without the header: the proxy itself is the client")
	void trustedWithoutHeader() {
		assertThat(resolver.clientKey(request(CADDY, null))).isEqualTo(CADDY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "not-an-ip", "999.1.1.1", "1.2.3", "1.2.3.4.5", "example.com", "1.2.3.4:80", "-1.2.3.4",
			"0x7f.0.0.1", "::g", "1.2.3.4, ", ",,," })
	@DisplayName("a malformed forwarded value falls back to the peer; it never triggers a DNS lookup or an error")
	void malformedFallsBack(String header) {
		assertThat(resolver.clientKey(request(CADDY, header))).isEqualTo(CADDY);
	}

	@Test
	@DisplayName("IPv6 clients are grouped by their /64: one household or attacker cannot get 2^64 buckets")
	void ipv6Grouping() {
		String a = resolver.clientKey(request(CADDY, "2001:db8:abcd:12:aaaa:bbbb:cccc:dddd"));
		String b = resolver.clientKey(request(CADDY, "2001:db8:abcd:12:1:2:3:4"));
		String other = resolver.clientKey(request(CADDY, "2001:db8:abcd:13::1"));

		assertThat(a).isEqualTo(b).isEqualTo("2001:db8:abcd:12::/64");
		assertThat(other).isNotEqualTo(a);
	}

	@Test
	@DisplayName("equivalent spellings of an IPv6 address give the same key")
	void ipv6Canonical() {
		assertThat(resolver.clientKey(request(CADDY, "2001:DB8:0:0:0:0:0:1")))
				.isEqualTo(resolver.clientKey(request(CADDY, "2001:db8::1")));
	}

	@Test
	@DisplayName("an IPv4-mapped IPv6 address is treated as the IPv4 address")
	void ipv4Mapped() {
		assertThat(resolver.clientKey(request(CADDY, "::ffff:198.51.100.7"))).isEqualTo("198.51.100.7");
	}

	@Test
	@DisplayName("a peer written as IPv6 loopback or with a scope is still a stable key")
	void peerKeys() {
		assertThat(resolver.clientKey(request("0:0:0:0:0:0:0:1", null))).isEqualTo("0:0:0:0::/64");
	}

	@Test
	@DisplayName("with no trusted proxies configured the header is never read")
	void noTrustedProxies() {
		ClientIpResolver none = new ClientIpResolver(new ProxyProperties(List.of()));

		assertThat(none.clientKey(request(CADDY, "198.51.100.7"))).isEqualTo(CADDY);
	}
}
