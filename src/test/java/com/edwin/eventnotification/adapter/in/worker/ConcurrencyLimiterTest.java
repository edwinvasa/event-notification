package com.edwin.eventnotification.adapter.in.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    @Test
    void acquireAndReleaseRoundTripWithoutBlocking() throws InterruptedException {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(10, 10);

        limiter.acquire("client-1");
        limiter.release("client-1");
    }

    @Test
    void perClientLimitBlocksASecondConcurrentAcquireForTheSameClient() throws InterruptedException {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(10, 1);
        limiter.acquire("client-a");

        Thread blocked = new Thread(() -> {
            try {
                limiter.acquire("client-a");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();
        blocked.join(300);
        assertThat(blocked.isAlive()).isTrue();

        limiter.release("client-a");
        blocked.join(2000);
        assertThat(blocked.isAlive()).isFalse();
    }

    @Test
    void differentClientsDoNotBlockEachOtherWhenGlobalCapacityAllows() throws InterruptedException {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(10, 1);

        limiter.acquire("client-a");
        limiter.acquire("client-b");
    }

    @Test
    void globalLimitBlocksAcquireEvenForDifferentClientsOnceExhausted() throws InterruptedException {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(1, 10);
        limiter.acquire("client-a");

        Thread blocked = new Thread(() -> {
            try {
                limiter.acquire("client-b");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();
        blocked.join(300);
        assertThat(blocked.isAlive()).isTrue();

        limiter.release("client-a");
        blocked.join(2000);
        assertThat(blocked.isAlive()).isFalse();
    }
}
