package com.edwin.eventnotification.adapter.in.worker;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConcurrencyLimiter {

    private final Semaphore global;
    private final int perClientLimit;
    private final ConcurrentHashMap<String, Semaphore> perClient = new ConcurrentHashMap<>();

    public ConcurrencyLimiter(
            @Value("${worker.concurrency.global}") int globalLimit,
            @Value("${worker.concurrency.per-client}") int perClientLimit) {
        this.global = new Semaphore(globalLimit);
        this.perClientLimit = perClientLimit;
    }

    public void acquire(String clientId) throws InterruptedException {
        Objects.requireNonNull(clientId, "clientId must not be null");
        perClient.computeIfAbsent(clientId, id -> new Semaphore(perClientLimit)).acquire();
        global.acquire();
    }

    public void release(String clientId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        global.release();
        Semaphore clientSemaphore = perClient.get(clientId);
        if (clientSemaphore != null) {
            clientSemaphore.release();
        }
    }

    /**
     * Snapshot of how many global delivery permits are currently free. Used by the Worker as a
     * best-effort backpressure signal to decide how much new work to claim, not as a hard
     * reservation: it does not account for tasks blocked waiting on a per-client permit, so a
     * single saturated client can still accumulate pending Virtual Threads of its own even while
     * this value stays high.
     */
    public int availableGlobalPermits() {
        return global.availablePermits();
    }
}
