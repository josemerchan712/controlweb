package dev.josemariamerchan.controlweb.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DnsHostResolverTest {

	private final DnsHostResolver resolver = new DnsHostResolver();

	@Test
	@DisplayName("resolves IP literals without a DNS lookup")
	void resolvesIpv4Literal() throws UnknownHostException {
		assertThat(resolver.resolve("93.184.216.34")).containsExactly(InetAddress.getByName("93.184.216.34"));
	}

	@Test
	@DisplayName("accepts IPv6 literals in the bracketed form used inside URLs")
	void resolvesBracketedIpv6Literal() throws UnknownHostException {
		assertThat(resolver.resolve("[::1]")).hasSize(1).allMatch(InetAddress::isLoopbackAddress);
	}

	@Test
	@DisplayName("resolves localhost to loopback addresses")
	void resolvesLocalhost() throws UnknownHostException {
		assertThat(resolver.resolve("localhost")).isNotEmpty().allMatch(InetAddress::isLoopbackAddress);
	}

	@Test
	@DisplayName("throws UnknownHostException for names that cannot be resolved (.invalid is reserved by RFC 2606)")
	void unknownHost() {
		assertThatThrownBy(() -> resolver.resolve("nope.invalid")).isInstanceOf(UnknownHostException.class);
	}
}
