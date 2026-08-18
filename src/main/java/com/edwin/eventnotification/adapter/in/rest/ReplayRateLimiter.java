package com.edwin.eventnotification.adapter.in.rest;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

/**
 * Per-client-id Token Bucket rate limiter for the replay endpoint (ADR-008 §2), backed by
 * Bucket4j instead of a hand-rolled token bucket algorithm.
 *
 * <p>State is kept in memory, per service instance, with no external store (e.g. Redis): in a
 * multi-instance deployment each instance enforces its own independent bucket per client, so the
 * effective ceiling scales with the number of instances rather than being a single shared limit.
 * A distributed store enforcing one global limit across instances is intentionally out of scope
 * for this iteration and would be a future evolution.
 */
@Component
public class ReplayRateLimiter {

    private final int capacity;
    private final Duration window;
    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ReplayRateLimiter(
            @Value("${replay.rate-limit.capacity}") int capacity,
            @Value("${replay.rate-limit.window}") Duration window,
            Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        Objects.requireNonNull(clock, "clock must not be null");
        this.capacity = capacity;
        this.window = window;
        this.timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return TimeUnit.MILLISECONDS.toNanos(clock.millis());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    /**
     * Attempts to consume one token for {@code clientId}. Allows an initial burst up to the
     * configured capacity, then refills continuously at {@code capacity / window} tokens per
     * second.
     */
    public RateLimitDecision tryAcquire(String clientId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> newBucket());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new RateLimitDecision(true, 0);
        }

        long nanosToWait = probe.getNanosToWaitForRefill();
        long retryAfterSeconds = Math.max(1, ceilNanosToSeconds(nanosToWait));
        return new RateLimitDecision(false, retryAfterSeconds);
    }

    private static long ceilNanosToSeconds(long nanos) {
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window).initialTokens(capacity))
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
