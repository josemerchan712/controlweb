package dev.josemariamerchan.controlweb.common.net;

import static org.assertj.core.api.Assertions.assertThat;
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
 * The EDGES of the blocked ranges. A mask that is one bit off blocks real public sites (the portfolio sits behind
 * Cloudflare addresses such as 172.67.x.x and 104.21.x.x, right next to 172.16.0.0/12 and 100.64.0.0/10) or lets a
 * private address through. Every boundary is tested on both sides, and the two ranges that sit inside a larger
 * "public-looking" block are swept octet by octet.
 */
class HostSafetyBoundaryTest {

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

	@ParameterizedTest(name = "allows {0}")
	@ValueSource(strings = {
			// the real Cloudflare addresses of the portfolio: next to 172.16.0.0/12 and 100.64.0.0/10 but public
			"172.67.164.248", "104.21.49.164",
			// Cloudflare IPv6 and the same IPv4 hidden in an IPv6 address
			"2606:4700:3030::6815:31a4", "::ffff:172.67.164.248", "64:ff9b::ac43:a4f8",
			// first address below and first above 172.16.0.0/12
			"172.15.255.255", "172.32.0.0",
			// first below and first above 100.64.0.0/10
			"100.63.255.255", "100.128.0.0",
			// neighbours of 10/8, 127/8, 169.254/16, 192.168/16 and of the multicast / reserved block
			"9.255.255.255", "11.0.0.0", "126.255.255.255", "128.0.0.0", "169.253.255.255", "169.255.0.0",
			"192.167.255.255", "192.169.0.0", "223.255.255.255", "1.0.0.1" })
	@DisplayName("public addresses next to a blocked range are allowed")
	void allowsTheOutsideOfEachEdge(String ip) {
		assertThatCode(() -> validatorResolvingTo(ip).assertSafe("site.example.com")).doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "blocks {0}")
	@ValueSource(strings = {
			// both ends of 172.16.0.0/12
			"172.16.0.0", "172.31.255.255", "172.16.0.1", "172.24.1.1",
			// both ends of 100.64.0.0/10
			"100.64.0.0", "100.127.255.255", "100.100.100.100",
			// both ends of 10/8, 127/8, 169.254/16, 192.168/16
			"10.0.0.0", "10.255.255.255", "127.0.0.0", "127.255.255.255", "169.254.0.0", "169.254.255.255",
			"169.254.169.254", "192.168.0.0", "192.168.255.255",
			// start of multicast / reserved, "this network"
			"224.0.0.0", "0.0.0.0", "0.255.255.255",
			// the same private addresses hidden in IPv6
			"::ffff:172.16.0.0", "::ffff:172.31.255.255", "64:ff9b::ac10:0", "::ffff:100.64.0.0" })
	@DisplayName("both ends of each blocked range are blocked")
	void blocksTheInsideOfEachEdge(String ip) {
		assertThatThrownBy(() -> validatorResolvingTo(ip).assertSafe("site.example.com"))
				.isInstanceOf(UnsafeHostException.class);
	}

	@Test
	@DisplayName("172.x.y.z is blocked exactly when x is 16..31: every value of x is checked")
	void sweepOf172() {
		for (int second = 0; second <= 255; second++) {
			boolean expected = second >= 16 && second <= 31;
			assertThat(blocked("172." + second + ".7.7")).as("172.%d.7.7", second).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("100.x.y.z is blocked exactly when x is 64..127: every value of x is checked")
	void sweepOf100() {
		for (int second = 0; second <= 255; second++) {
			boolean expected = second >= 64 && second <= 127;
			assertThat(blocked("100." + second + ".7.7")).as("100.%d.7.7", second).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("192.x.y.z and 169.x.y.z: only 192.168/16 and 169.254/16 are blocked, every second octet is checked")
	void sweepOf192And169() {
		for (int second = 0; second <= 255; second++) {
			assertThat(blocked("192." + second + ".7.7")).as("192.%d.7.7", second).isEqualTo(second == 168);
			assertThat(blocked("169." + second + ".7.7")).as("169.%d.7.7", second).isEqualTo(second == 254);
		}
	}

	@Test
	@DisplayName("a host whose DNS answer mixes a Cloudflare address with a private one is still blocked")
	void mixedAnswerWithCloudflareIsBlocked() {
		HostSafetyValidator validator = new HostSafetyValidator(host -> new InetAddress[] {
				InetAddress.getByName("172.67.164.248"), InetAddress.getByName("172.16.0.1") }, new CheckerProperties(false));

		assertThatThrownBy(() -> validator.assertSafe("site.example.com")).isInstanceOf(UnsafeHostException.class);
	}

	@Test
	@DisplayName("the two Cloudflare addresses of the portfolio pass together as one DNS answer")
	void cloudflareAnswerPasses() throws UnknownHostException {
		HostSafetyValidator validator = new HostSafetyValidator(host -> new InetAddress[] {
				InetAddress.getByName("172.67.164.248"), InetAddress.getByName("104.21.49.164") }, new CheckerProperties(false));

		assertThatCode(() -> validator.assertSafe("josemariamerchan.dev")).doesNotThrowAnyException();
	}
}
