package dev.josemariamerchan.controlweb.publicstatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.josemariamerchan.controlweb.common.text.MessageSanitizer;
import dev.josemariamerchan.controlweb.config.PublicProperties;

/**
 * Keeps the computed public answer in memory for a short time ({@code app.public.cache-ttl}, 15 s) so that however
 * many requests arrive, the database is read at most once per TTL. This is what protects the database from the one
 * endpoint anyone on the Internet can call.
 *
 * <ul>
 * <li><b>Single flight</b>: when the answer is stale and many requests arrive together, ONE of them reloads it while
 * the others wait for that result; they never start reloads of their own.</li>
 * <li>Time comes from the application's {@link Clock}, so tests can move it.</li>
 * <li><b>Stale on error</b>: if a reload fails, the previous answer is served as long as it is younger than
 * {@value #STALE_LIMIT_IN_TTLS} times the TTL (its {@code generatedAt} tells the truth about its age); beyond that, or
 * if there is no previous answer, the request fails with {@link PublicStatusUnavailableException} (a plain 503).</li>
 * </ul>
 */
@Component
public class PublicStatusCache {

	static final int STALE_LIMIT_IN_TTLS = 10;

	private static final Logger log = LoggerFactory.getLogger(PublicStatusCache.class);

	private record Entry(PublicStatusResponse response, Instant loadedAt) {
	}

	private final PublicStatusLoader loader;
	private final Clock clock;
	private final Duration ttl;
	private final Object reloadLock = new Object();
	private volatile Entry entry;

	public PublicStatusCache(PublicStatusLoader loader, Clock clock, PublicProperties properties) {
		this.loader = loader;
		this.clock = clock;
		this.ttl = properties.cacheTtl();
	}

	/** @throws PublicStatusUnavailableException if there is nothing recent to serve and the reload failed */
	public PublicStatusResponse get() {
		Entry current = entry;
		if (isFresh(current, clock.instant())) {
			return current.response();
		}
		synchronized (reloadLock) {
			Instant now = clock.instant();
			current = entry; // another request may have reloaded while this one waited for the lock
			if (isFresh(current, now)) {
				return current.response();
			}
			try {
				PublicStatusResponse loaded = loader.load();
				entry = new Entry(loaded, now);
				return loaded;
			}
			catch (RuntimeException ex) {
				// Only the type and a sanitized message are logged: they could otherwise carry connection details.
				log.warn("Could not refresh the public status: {} {}", ex.getClass().getName(),
						MessageSanitizer.sanitize(ex.getMessage()));
				if (current != null && !current.loadedAt().plus(ttl.multipliedBy(STALE_LIMIT_IN_TTLS)).isBefore(now)) {
					return current.response();
				}
				throw new PublicStatusUnavailableException();
			}
		}
	}

	/** Forgets the stored answer, so the next request reads the database. For tests and future administration. */
	public void invalidate() {
		entry = null;
	}

	private boolean isFresh(Entry candidate, Instant now) {
		return candidate != null && now.isBefore(candidate.loadedAt().plus(ttl));
	}
}
