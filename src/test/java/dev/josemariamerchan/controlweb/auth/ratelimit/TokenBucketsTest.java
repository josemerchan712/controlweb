package dev.josemariamerchan.controlweb.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.josemariamerchan.controlweb.TestClock;

/** The token bucket itself: capacity, refill, waiting time, memory bound. All time comes from a fixed Clock. */
class TokenBucketsTest {

	private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	private final TestClock clock = new TestClock();
	private TokenBuckets buckets;

	@BeforeEach
	void setUp() {
		clock.fix(T0);
		// 3 tokens, one more every 60 s
		buckets = new TokenBuckets(3, Duration.ofSeconds(60), 100, clock);
	}

	@Test
	@DisplayName("a new key starts full: it can be consumed `capacity` times and then it is empty")
	void startsFull() {
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);

		assertThat(buckets.tryConsume("a")).isPositive();
	}

	@Test
	@DisplayName("keys are independent")
	void keysAreIndependent() {
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}

		assertThat(buckets.tryConsume("a")).isPositive();
		assertThat(buckets.tryConsume("b")).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("the wait is the time until the next token, never zero while the bucket is empty")
	void waitTime() {
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ofSeconds(60));

		clock.fix(T0.plusSeconds(45));
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ofSeconds(15));

		clock.fix(T0.plusMillis(59_999));
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ofMillis(1));
	}

	@Test
	@DisplayName("one token comes back every interval, exactly at the boundary and not a moment before")
	void refillBoundary() {
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}

		clock.fix(T0.plusMillis(59_999));
		assertThat(buckets.tryConsume("a")).isPositive();

		clock.fix(T0.plusSeconds(60));
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isPositive();
	}

	@Test
	@DisplayName("partial progress is kept: consuming again does not restart the interval")
	void partialProgressIsKept() {
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}
		clock.fix(T0.plusSeconds(40));
		assertThat(buckets.tryConsume("a")).isPositive(); // still empty, 20 s to go

		clock.fix(T0.plusSeconds(60));
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("it never holds more than `capacity` tokens, however long it was idle")
	void neverExceedsCapacity() {
		buckets.tryConsume("a");
		clock.fix(T0.plus(Duration.ofDays(30)));

		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isEqualTo(Duration.ZERO);
		assertThat(buckets.tryConsume("a")).isPositive();
	}

	@Test
	@DisplayName("a clock that goes backwards never grants extra tokens")
	void clockGoingBackwards() {
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}
		clock.fix(T0.minusSeconds(3600));

		assertThat(buckets.tryConsume("a")).isPositive();
	}

	@Test
	@DisplayName("peek tells whether a token is available without taking it")
	void peekDoesNotConsume() {
		for (int i = 0; i < 10; i++) {
			assertThat(buckets.peek("a")).isEqualTo(Duration.ZERO);
		}
		for (int i = 0; i < 3; i++) {
			buckets.tryConsume("a");
		}

		assertThat(buckets.peek("a")).isEqualTo(Duration.ofSeconds(60));
		assertThat(buckets.peek("a")).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	@DisplayName("consume takes a token when there is one and does nothing (never negative) when the bucket is empty")
	void consumeNeverGoesNegative() {
		for (int i = 0; i < 10; i++) {
			buckets.consume("a");
		}

		clock.fix(T0.plusSeconds(60));
		assertThat(buckets.peek("a")).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("memory is bounded: the least recently used keys are dropped beyond maxKeys")
	void memoryIsBounded() {
		TokenBuckets small = new TokenBuckets(3, Duration.ofSeconds(60), 100, clock);
		for (int i = 0; i < 250; i++) {
			small.tryConsume("key-" + i);
		}

		assertThat(small.size()).isEqualTo(100);
	}

	@Test
	@DisplayName("recently used keys survive eviction")
	void recentlyUsedKeysSurvive() {
		TokenBuckets small = new TokenBuckets(1, Duration.ofSeconds(60), 100, clock);
		small.tryConsume("hot");
		for (int i = 0; i < 250; i++) {
			small.tryConsume("key-" + i);
			small.peek("hot"); // keeps it recent
		}

		assertThat(small.peek("hot")).isPositive(); // its empty bucket was kept, not reset by eviction
	}
}
