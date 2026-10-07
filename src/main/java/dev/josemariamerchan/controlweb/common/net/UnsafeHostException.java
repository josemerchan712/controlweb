package dev.josemariamerchan.controlweb.common.net;

/** Thrown when a host resolves to an address the application must never connect to (SSRF guard). */
public class UnsafeHostException extends RuntimeException {

	public UnsafeHostException(String host) {
		super("Host '%s' is not allowed: it resolves to a loopback, private, link-local or otherwise non-public address"
				.formatted(host));
	}
}
