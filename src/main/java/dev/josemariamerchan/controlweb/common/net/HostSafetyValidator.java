package dev.josemariamerchan.controlweb.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.config.CheckerProperties;

/**
 * SSRF guard: refuses hosts that resolve to loopback, private, link-local, multicast, reserved or
 * otherwise non-public addresses, so the monitor cannot be used to probe the internal network or
 * the cloud metadata endpoint (169.254.169.254).
 *
 * <p>It re-resolves the host on every call. Callers must therefore invoke it right before each
 * request (and each redirect hop), not only when a site is saved, because DNS can change.
 *
 * <p>A host that does not resolve at all is accepted: there is nothing to attack, and the checker
 * records such a site as down. Known limitation: DNS may still change between this check and the
 * actual connection (DNS rebinding). Closing that window would require pinning the resolved IP
 * in the connection, which is out of scope for the MVP.
 */
@Component
public class HostSafetyValidator {

	private final HostResolver resolver;
	private final CheckerProperties properties;

	public HostSafetyValidator(HostResolver resolver, CheckerProperties properties) {
		this.resolver = resolver;
		this.properties = properties;
	}

	/**
	 * @throws UnsafeHostException if any address of the host is not public
	 */
	public void assertSafe(String host) {
		if (properties.allowPrivateHosts()) {
			return;
		}
		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(host);
		}
		catch (UnknownHostException ex) {
			return;
		}
		for (InetAddress address : addresses) {
			if (isForbidden(address)) {
				throw new UnsafeHostException(host);
			}
		}
	}

	static boolean isForbidden(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			return isForbiddenIpv4(bytes[0] & 0xFF, bytes[1] & 0xFF);
		}
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		// Unique local addresses, fc00::/7.
		if ((bytes[0] & 0xFE) == 0xFC) {
			return true;
		}
		// IPv4 hidden inside IPv6 must be judged by the IPv4 rules.
		byte[] embedded = embeddedIpv4(bytes);
		return embedded != null && isForbiddenIpv4(embedded[0] & 0xFF, embedded[1] & 0xFF);
	}

	private static boolean isForbiddenIpv4(int first, int second) {
		return first == 0 // "this network"
				|| first == 10 // private
				|| (first == 100 && (second & 0xC0) == 64) // 100.64.0.0/10 shared address space (CGNAT)
				|| first == 127 // loopback
				|| (first == 169 && second == 254) // link-local, cloud metadata
				|| (first == 172 && (second & 0xF0) == 16) // 172.16.0.0/12 private
				|| (first == 192 && second == 168) // private
				|| first >= 224; // multicast, reserved and broadcast
	}

	/** Returns the 4 bytes of an IPv4 address embedded in an IPv6 one, or null if there is none. */
	private static byte[] embeddedIpv4(byte[] b) {
		boolean first80BitsZero = true;
		for (int i = 0; i < 10; i++) {
			first80BitsZero &= b[i] == 0;
		}
		// ::a.b.c.d (IPv4-compatible) and ::ffff:a.b.c.d (IPv4-mapped)
		if (first80BitsZero && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF))) {
			return new byte[] { b[12], b[13], b[14], b[15] };
		}
		// 64:ff9b::/96 (NAT64)
		if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xFF && b[3] == (byte) 0x9B) {
			boolean middleZero = true;
			for (int i = 4; i < 12; i++) {
				middleZero &= b[i] == 0;
			}
			if (middleZero) {
				return new byte[] { b[12], b[13], b[14], b[15] };
			}
		}
		// 2002::/16 (6to4)
		if (b[0] == 0x20 && b[1] == 0x02) {
			return new byte[] { b[2], b[3], b[4], b[5] };
		}
		return null;
	}
}
