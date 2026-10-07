package dev.josemariamerchan.controlweb.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a host name to its IP addresses. It is an interface so that tests can simulate DNS
 * (including a name that changes from a public to a private address).
 */
@FunctionalInterface
public interface HostResolver {

	/**
	 * @param host a host name, or an IP literal (IPv6 literals may come in brackets, as in a URL)
	 * @return every address the host resolves to, never empty
	 * @throws UnknownHostException if the host cannot be resolved
	 */
	InetAddress[] resolve(String host) throws UnknownHostException;
}
