package com.edwin.eventnotification.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ReplayRateLimiterTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final int CAPACITY = 20;

    private ReplayRateLimiter limiter(Clock clock) {
        return new ReplayRateLimiter(CAPACITY, Duration.ofMinutes(1), clock);
    }

    @Test
    void allowsABurstUpToCapacity() {
        ReplayRateLimiter limiter = limiter(Clock.fixed(START, ZoneOffset.UTC));

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryAcquire("client-1").allowed()).isTrue();
        }
    }

    @Test
    void rejectsTheRequestImmediatelyAfterTheBurstIsExhausted() {
        ReplayRateLimiter limiter = limiter(Clock.fixed(START, ZoneOffset.UTC));
        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryAcquire("client-1");
        }

        RateLimitDecision decision = limiter.tryAcquire("client-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(3);
    }

    @Test
    void tokenBecomesAvailableAgainAfterTheRefillIntervalElapses() {
        MutableClock clock = new MutableClock(START);
        ReplayRateLimiter limiter = limiter(clock);
        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryAcquire("client-1");
        }
        assertThat(limiter.tryAcquire("client-1").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(3));

        assertThat(limiter.tryAcquire("client-1").allowed()).isTrue();
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        ReplayRateLimiter limiter = limiter(Clock.fixed(START, ZoneOffset.UTC));
        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryAcquire("client-1");
        }

        assertThat(limiter.tryAcquire("client-1").allowed()).isFalse();
        assertThat(limiter.tryAcquire("client-2").allowed()).isTrue();
    }

    @Test
    void doesNotAllowMoreThanCapacityEvenAfterALongIdlePeriod() {
        MutableClock clock = new MutableClock(START);
        ReplayRateLimiter limiter = limiter(clock);

        clock.advance(Duration.ofHours(1));

        int allowedCount = 0;
        for (int i = 0; i < CAPACITY + 5; i++) {
            if (limiter.tryAcquire("client-1").allowed()) {
                allowedCount++;
            }
        }

        assertThat(allowedCount).isEqualTo(CAPACITY);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
