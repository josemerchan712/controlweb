package dev.josemariamerchan.controlweb.config;

import java.net.InetAddress;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import dev.josemariamerchan.controlweb.common.net.IpLiterals;

/**
 * Reverse proxies whose {@code X-Forwarded-For} header is believed (env var {@code TRUSTED_PROXY_ADDRESSES}).
 *
 * <p>Trust is a list of EXACT IP addresses, never a range, a host name or a wildcard: a range would let any other
 * container that ends up in the same network forge the header and pick its own rate-limit bucket. Empty (the default)
 * means the header is never read.
 *
 * @param trustedAddresses comma separated IPv4/IPv6 literals, e.g. {@code 10.77.0.10}
 */
@ConfigurationProperties("app.proxy")
public record ProxyProperties(@DefaultValue List<String> trustedAddresses) {

	public ProxyProperties {
		List<String> clean = trustedAddresses == null ? List.of()
				: trustedAddresses.stream().map(String::strip).filter(address -> !address.isEmpty()).toList();
		for (String address : clean) {
			InetAddress parsed = IpLiterals.parse(address).orElse(null);
			if (parsed == null || parsed.isAnyLocalAddress()) {
				throw new InvalidSecurityConfigurationException(
						"TRUSTED_PROXY_ADDRESSES must contain exact IP addresses: ranges, names, wildcards and 'any' addresses are not allowed.",
						"Set TRUSTED_PROXY_ADDRESSES to the exact IP of your reverse proxy, e.g. 10.77.0.10 (several: comma separated), or leave it empty.");
			}
		}
		trustedAddresses = clean;
	}
}
