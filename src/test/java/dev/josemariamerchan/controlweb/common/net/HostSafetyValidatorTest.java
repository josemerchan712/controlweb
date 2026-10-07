package dev.josemariamerchan.controlweb.common.net;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.josemariamerchan.controlweb.config.CheckerProperties;

/**
 * SSRF guard. DNS is simulated through the injectable {@link HostResolver}, so these tests are
 * deterministic and never touch the network.
 */
class HostSafetyValidatorTest {

	private static HostResolver resolvingTo(String... literals) {
		return host -> {
			InetAddress[] addresses = new InetAddress[literals.length];
			for (int i = 0; i < literals.length; i++) {
				addresses[i] = InetAddress.getByName(literals[i]);
			}
			return addresses;
		};
	}

	private static HostSafetyValidator strict(HostResolver resolver) {
		return new HostSafetyValidator(resolver, new CheckerProperties(false));
	}

	@ParameterizedTest(name = "blocks {0}")
	@ValueSource(strings = {
			// loopback
			"127.0.0.1", "127.1.2.3", "::1",
			// private (RFC 1918) and unique local IPv6 (fc00::/7)
			"10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.1", "fc00::1", "fd12:3456:789a::1",
			// link-local, including the cloud metadata endpoint
			"169.254.169.254", "169.254.0.1", "fe80::1",
			// multicast
			"224.0.0.1", "239.255.255.255", "ff02::1",
			// unspecified / "this network"
			"0.0.0.0", "0.1.2.3", "::",
			// shared address space (CGNAT), reserved and broadcast
			"100.64.0.1", "100.127.255.255", "240.0.0.1", "255.255.255.255",
			// IPv4 hidden inside IPv6: mapped, compatible, NAT64 and 6to4
			"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1"
	})
	void blocksNonPublicAddresses(String ip) {
		HostSafetyValidator validator = strict(resolvingTo(ip));

		assertThatThrownBy(() -> validator.assertSafe("some.example.com")).isInstanceOf(UnsafeHostException.class);
	}

	@ParameterizedTest(name = "allows {0}")
	@ValueSource(strings = {
			"93.184.216.34", "8.8.8.8", "1.1.1.1",
			// just outside the private / CGNAT ranges
			"172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.169.0.1", "11.0.0.1",
			"2606:2800:220:1:248:1893:25c8:1946",
			// public IPv4 embedded in NAT64 / 6to4
			"64:ff9b::5db8:d822", "2002:5db8:d822::1"
	})
	void allowsPublicAddresses(String ip) {
		HostSafetyValidator validator = strict(resolvingTo(ip));

		assertThatCode(() -> validator.assertSafe("some.example.com")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("blocks the host if ANY of its addresses is non-public (mixed DNS answer)")
	void blocksWhenAnyAddressIsPrivate() {
		HostSafetyValidator validator = strict(resolvingTo("93.184.216.34", "10.0.0.5"));

		assertThatThrownBy(() -> validator.assertSafe("mixed.example.com")).isInstanceOf(UnsafeHostException.class);
	}

	@Test
	@DisplayName("re-resolves on every call, so a DNS change from public to private is caught")
	void reResolvesOnEveryCall() {
		String[] current = { "93.184.216.34" };
		HostSafetyValidator validator = strict(host -> new InetAddress[] { InetAddress.getByName(current[0]) });

		assertThatCode(() -> validator.assertSafe("rebinding.example.com")).doesNotThrowAnyException();

		current[0] = "169.254.169.254";

		assertThatThrownBy(() -> validator.assertSafe("rebinding.example.com")).isInstanceOf(UnsafeHostException.class);
	}

	@Test
	@DisplayName("with allow-private-hosts=true everything passes and DNS is not even consulted")
	void allowPrivateHostsBypassesTheCheck() {
		HostResolver mustNotBeCalled = host -> {
			throw new AssertionError("resolver must not be called when private hosts are allowed");
		};
		HostSafetyValidator validator = new HostSafetyValidator(mustNotBeCalled, new CheckerProperties(true));

		assertThatCode(() -> validator.assertSafe("localhost")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("an unresolvable host is not an SSRF risk: it is accepted (the checker will record it as down)")
	void unresolvableHostIsAccepted() {
		HostSafetyValidator validator = strict(host -> {
			throw new UnknownHostException(host);
		});

		assertThatCode(() -> validator.assertSafe("nope.invalid")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the error names the host but does not leak the resolved IP")
	void errorDoesNotLeakTheAddress() {
		HostSafetyValidator validator = strict(resolvingTo("10.1.2.3"));

		assertThatThrownBy(() -> validator.assertSafe("internal.example.com"))
				.isInstanceOf(UnsafeHostException.class)
				.hasMessageContaining("internal.example.com")
				.hasMessageNotContaining("10.1.2.3");
	}
}
