package dev.josemariamerchan.controlweb.auth.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.common.net.IpLiterals;
import dev.josemariamerchan.controlweb.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Works out which client a request comes from, for rate limiting.
 *
 * <p>{@code X-Forwarded-For} is a header any client can send, so it is believed ONLY when the TCP peer is exactly one of
 * the configured trusted proxies (our Caddy). In that case the RIGHTMOST entry is used: it is the one our proxy wrote,
 * entries to its left were supplied by the client. A missing or malformed value falls back to the peer.
 *
 * <p>The result is a bucket key, not an address to show: IPv4 as is, IPv6 as its /64 prefix (one subscriber normally
 * owns a whole /64, so grouping stops one attacker from having 2^64 buckets).
 */
@Component
public class ClientIpResolver {

	static final String FORWARDED_FOR = "X-Forwarded-For";
	private static final String UNKNOWN = "unknown";

	private final Set<InetAddress> trustedProxies;

	public ClientIpResolver(ProxyProperties properties) {
		this.trustedProxies = properties.trustedAddresses().stream()
				.map(IpLiterals::parse).flatMap(Optional::stream).collect(Collectors.toUnmodifiableSet());
	}

	public String clientKey(HttpServletRequest request) {
		Optional<InetAddress> peer = IpLiterals.parse(request.getRemoteAddr());
		if (peer.isEmpty()) {
			return UNKNOWN;
		}
		if (trustedProxies.contains(peer.get())) {
			Optional<InetAddress> forwarded = rightmostForwarded(request);
			if (forwarded.isPresent()) {
				return keyOf(forwarded.get());
			}
		}
		return keyOf(peer.get());
	}

	private static Optional<InetAddress> rightmostForwarded(HttpServletRequest request) {
		List<String> headers = Collections.list(request.getHeaders(FORWARDED_FOR));
		if (headers.isEmpty()) {
			return Optional.empty();
		}
		String[] entries = headers.get(headers.size() - 1).split(",", -1);
		return IpLiterals.parse(entries[entries.length - 1]);
	}

	private static String keyOf(InetAddress address) {
		if (address instanceof Inet6Address) {
			byte[] bytes = address.getAddress();
			StringBuilder key = new StringBuilder();
			for (int group = 0; group < 4; group++) {
				if (group > 0) {
					key.append(':');
				}
				key.append(Integer.toHexString(((bytes[group * 2] & 0xff) << 8) | (bytes[group * 2 + 1] & 0xff)));
			}
			return key.append("::/64").toString();
		}
		return address.getHostAddress();
	}
}
