package dev.josemariamerchan.controlweb.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.josemariamerchan.controlweb.config.CheckerProperties;

/**
 * Documentation and benchmarking ranges that never route on the public internet and that the planned egress firewall
 * also blocks: 192.0.0.0/24 (IETF protocol assignments), 192.0.2.0/24 (TEST-NET-1), 198.18.0.0/15 (benchmarking),
 * 198.51.100.0/24 (TEST-NET-2) and 203.0.113.0/24 (TEST-NET-3). Both layers must agree. Same style as
 * {@link HostSafetyBoundaryTest}: both ends blocked, the immediate neighbours allowed, also hidden in IPv6.
 */
class HostSafetyDocumentationRangesTest {

	private static HostSafetyValidator validatorResolvingTo(String literal) {
		return new HostSafetyValidator(host -> new InetAddress[] { InetAddress.getByName(literal) },
				new CheckerProperties(false));
	}

	private static boolean blocked(String literal) {
		try {
			validatorResolvingTo(literal).assertSafe("site.example.com");
			return false;
		}
		catch (UnsafeHostException ex) {
			return true;
		}
	}

	@ParameterizedTest(name = "blocks {0}")
	@ValueSource(strings = {
			// 192.0.0.0/24
			"192.0.0.0", "192.0.0.1", "192.0.0.255",
			// 192.0.2.0/24
			"192.0.2.0", "192.0.2.1", "192.0.2.255",
			// 198.18.0.0/15: both /16 halves, first and last address
			"198.18.0.0", "198.18.255.255", "198.19.0.0", "198.19.255.255",
			// 198.51.100.0/24
			"198.51.100.0", "198.51.100.1", "198.51.100.255",
			// 203.0.113.0/24
			"203.0.113.0", "203.0.113.1", "203.0.113.255",
			// the same addresses hidden in IPv6: mapped, NAT64 and 6to4
			"::ffff:192.0.0.1", "::ffff:192.0.2.1", "::ffff:198.18.0.1", "::ffff:198.19.255.255", "::ffff:198.51.100.7",
			"::ffff:203.0.113.5", "64:ff9b::c000:201", "64:ff9b::c633:6401", "2002:c000:201::1", "2002:cb00:7105::1" })
	@DisplayName("both ends of each documentation / benchmarking range are blocked")
	void blocksTheRanges(String ip) {
		assertThatThrownBy(() -> validatorResolvingTo(ip).assertSafe("site.example.com"))
				.isInstanceOf(UnsafeHostException.class);
	}

	@ParameterizedTest(name = "allows {0}")
	@ValueSource(strings = {
			// around 192.0.0.0/24 and 192.0.2.0/24
			"191.255.255.255", "192.0.1.0", "192.0.1.255", "192.0.3.0", "192.0.3.255", "192.1.0.0",
			// around 198.18.0.0/15
			"198.17.255.255", "198.20.0.0",
			// around 198.51.100.0/24 (the same /16, other third octets)
			"198.51.99.255", "198.51.101.0", "198.51.0.1", "198.51.255.255",
			// around 203.0.113.0/24
			"203.0.112.255", "203.0.114.0", "203.0.0.1", "203.1.113.5",
			// the same neighbours hidden in IPv6
			"::ffff:192.0.1.1", "::ffff:192.0.3.0", "::ffff:198.20.0.0", "::ffff:198.51.101.1", "::ffff:203.0.114.0",
			"64:ff9b::c000:101", "2002:c000:301::1" })
	@DisplayName("the immediate neighbours of each range are allowed")
	void allowsTheNeighbours(String ip) {
		assertThatCode(() -> validatorResolvingTo(ip).assertSafe("site.example.com")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("192.0.x.1 is blocked exactly for x = 0 and x = 2: every third octet is checked")
	void sweepOf192Dot0() {
		for (int third = 0; third <= 255; third++) {
			assertThat(blocked("192.0." + third + ".1")).as("192.0.%d.1", third).isEqualTo(third == 0 || third == 2);
		}
	}

	@Test
	@DisplayName("198.x.0.1 is blocked exactly for x = 18 and 19; 198.51.x.1 exactly for x = 100")
	void sweepOf198() {
		for (int second = 0; second <= 255; second++) {
			assertThat(blocked("198." + second + ".0.1")).as("198.%d.0.1", second).isEqualTo(second == 18 || second == 19);
		}
		for (int third = 0; third <= 255; third++) {
			assertThat(blocked("198.51." + third + ".1")).as("198.51.%d.1", third).isEqualTo(third == 100);
		}
	}

	@Test
	@DisplayName("203.0.x.1 is blocked exactly for x = 113; 203.x.113.1 for no x")
	void sweepOf203() {
		for (int third = 0; third <= 255; third++) {
			assertThat(blocked("203.0." + third + ".1")).as("203.0.%d.1", third).isEqualTo(third == 113);
		}
		for (int second = 1; second <= 255; second++) {
			assertThat(blocked("203." + second + ".113.1")).as("203.%d.113.1", second).isFalse();
		}
	}

	@Test
	@DisplayName("192.x.0.1: only x = 0 (protocol assignments) and 168 (private) are blocked, every second octet is checked")
	void sweepOf192() {
		for (int second = 0; second <= 255; second++) {
			assertThat(blocked("192." + second + ".0.1")).as("192.%d.0.1", second).isEqualTo(second == 0 || second == 168);
		}
	}

	@Test
	@DisplayName("a DNS answer mixing a documentation address with a public one is blocked")
	void mixedAnswerIsBlocked() {
		HostSafetyValidator validator = new HostSafetyValidator(host -> new InetAddress[] {
				InetAddress.getByName("93.184.216.34"), InetAddress.getByName("203.0.113.9") }, new CheckerProperties(false));

		assertThatThrownBy(() -> validator.assertSafe("site.example.com")).isInstanceOf(UnsafeHostException.class);
	}
}
