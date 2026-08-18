package com.edwin.eventnotification.domain.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

public class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(Duration baseDelay, Duration maxDelay) {
        this.baseDelay = Objects.requireNonNull(baseDelay, "baseDelay must not be null");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay must not be null");
    }

    public Instant computeNextAttemptAt(int attemptCount, Instant now, Duration retryAfter, RandomGenerator random) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(random, "random must not be null");

        if (retryAfter != null) {
            return now.plus(retryAfter);
        }

        return now.plus(fullJitterDelay(attemptCount, random));
    }

    private Duration fullJitterDelay(int attemptCount, RandomGenerator random) {
        double exponentialMillis = baseDelay.toMillis() * Math.pow(2, Math.max(attemptCount, 0));
        long cappedMillis = (long) Math.min(exponentialMillis, maxDelay.toMillis());
        long jitteredMillis = (long) (random.nextDouble() * cappedMillis);
        return Duration.ofMillis(jitteredMillis);
    }
}
