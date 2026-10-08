package dev.josemariamerchan.controlweb.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A set of token buckets, one per key. A bucket starts full ({@code capacity} tokens) and gets one token back every
 * {@code refillEvery}; taking a token when there is none is refused with the time to wait.
 *
 * <p>All time comes from the injected {@link Clock}, so tests move it by hand. Memory is bounded: beyond
 * {@code maxKeys} keys the least recently used one is forgotten (a forgotten bucket is a full one, so the only way to use
 * this to reset somebody else's bucket is to make {@code maxKeys} other keys more recent). Single node, in memory:
 * a restart empties it.
 */
final class TokenBuckets {

	private static final class Bucket {
		long tokens;
		Instant lastRefill;
	}

	private final int capacity;
	private final Duration refillEvery;
	private final Clock clock;
	private final Map<String, Bucket> buckets;

	TokenBuckets(int capacity, Duration refillEvery, int maxKeys, Clock clock) {
		this.capacity = capacity;
		this.refillEvery = refillEvery;
		this.clock = clock;
		this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
				return size() > maxKeys;
			}
		};
	}

	/** Takes one token. @return {@link Duration#ZERO} if it was taken, otherwise how long to wait for the next one */
	synchronized Duration tryConsume(String key) {
		Instant now = clock.instant();
		Bucket bucket = bucket(key, now);
		if (bucket.tokens > 0) {
			bucket.tokens--;
			return Duration.ZERO;
		}
		return waitTime(bucket, now);
	}

	/** Takes one token if there is one; does nothing when the bucket is already empty. */
	synchronized void consume(String key) {
		Instant now = clock.instant();
		Bucket bucket = bucket(key, now);
		if (bucket.tokens > 0) {
			bucket.tokens--;
		}
	}

	/** Like {@link #tryConsume} but takes nothing. */
	synchronized Duration peek(String key) {
		Instant now = clock.instant();
		Bucket bucket = buckets.get(key);
		if (bucket == null) {
			return Duration.ZERO; // never seen: a full bucket
		}
		refill(bucket, now);
		return bucket.tokens > 0 ? Duration.ZERO : waitTime(bucket, now);
	}

	synchronized int size() {
		return buckets.size();
	}

	synchronized void clear() {
		buckets.clear();
	}

	private Bucket bucket(String key, Instant now) {
		Bucket bucket = buckets.get(key);
		if (bucket == null) {
			bucket = new Bucket();
			bucket.tokens = capacity;
			bucket.lastRefill = now;
			buckets.put(key, bucket);
		}
		else {
			refill(bucket, now);
		}
		return bucket;
	}

	private void refill(Bucket bucket, Instant now) {
		if (bucket.tokens >= capacity) {
			bucket.lastRefill = now;
			return;
		}
		Duration elapsed = Duration.between(bucket.lastRefill, now);
		if (elapsed.isNegative()) {
			bucket.lastRefill = now; // the clock went backwards: never grant tokens for it
			return;
		}
		long whole = elapsed.dividedBy(refillEvery);
		if (whole > 0) {
			bucket.tokens = Math.min(capacity, bucket.tokens + whole);
			// keep the leftover fraction of the interval, unless the bucket is full again
			bucket.lastRefill = bucket.tokens >= capacity ? now : bucket.lastRefill.plus(refillEvery.multipliedBy(whole));
		}
	}

	private Duration waitTime(Bucket bucket, Instant now) {
		Duration remaining = refillEvery.minus(Duration.between(bucket.lastRefill, now));
		return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
	}
}
