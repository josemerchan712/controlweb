package dev.josemariamerchan.controlweb.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

/** Default {@link HostResolver}: uses the system resolver. */
@Component
public class DnsHostResolver implements HostResolver {

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		return InetAddress.getAllByName(bare);
	}
}
