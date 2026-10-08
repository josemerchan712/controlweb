package dev.josemariamerchan.controlweb.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses IP address LITERALS and nothing else. Unlike {@code InetAddress.getByName}, a host name is never accepted, so
 * parsing can never trigger a DNS lookup, and unusual spellings that some parsers accept (octal or hexadecimal octets,
 * short forms such as {@code 127.1}, a port, a scope id) are rejected instead of being reinterpreted.
 */
public final class IpLiterals {

	private static final Pattern IPV4 = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");
	private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

	private IpLiterals() {
	}

	/**
	 * @return the address, or empty if {@code text} is not exactly an IPv4 or IPv6 literal. An IPv4-mapped IPv6 address
	 *         ({@code ::ffff:1.2.3.4}) is returned as the IPv4 address.
	 */
	public static Optional<InetAddress> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		String value = text.strip();
		try {
			if (IPV4.matcher(value).matches()) {
				String[] parts = value.split("\\.");
				byte[] bytes = new byte[4];
				for (int i = 0; i < 4; i++) {
					int octet = Integer.parseInt(parts[i]);
					if (octet > 255) {
						return Optional.empty();
					}
					bytes[i] = (byte) octet;
				}
				return Optional.of(InetAddress.getByAddress(bytes));
			}
			if (IPV6_CHARS.matcher(value).matches() && value.chars().filter(c -> c == ':').count() >= 2) {
				// Only hex digits, colons and dots, with at least two colons: this is parsed as a literal, never resolved.
				return Optional.of(InetAddress.getByName(value));
			}
		}
		catch (UnknownHostException | NumberFormatException ex) {
			return Optional.empty();
		}
		return Optional.empty();
	}
}
