package com.edwin.eventnotification.adapter.in.worker;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.edwin.eventnotification.application.port.in.DeliverNotificationPort;
import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;

import jakarta.annotation.PreDestroy;

/**
 * Polls for due notifications and dispatches each to {@link DeliverNotificationPort} on its own
 * Virtual Thread, gated by {@link ConcurrencyLimiter}.
 *
 * <p>Backpressure: each poll caps how many notifications it claims to the currently available
 * global delivery permits ({@link ConcurrencyLimiter#availableGlobalPermits()}), so Virtual
 * Threads do not keep piling up waiting on a fully saturated global limit. This is a best-effort,
 * global-only signal: a single client that is itself the bottleneck (its own per-client permits
 * exhausted while the global limit still has room) can still accumulate Virtual Threads blocked
 * on its own per-client semaphore across successive polls, since those threads never reach the
 * global semaphore while waiting. Bounding that per-client backlog is intentionally out of scope
 * here (see ADR-007's still-open "estrategia exacta para los semáforos por cliente").
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final NotificationClaimRepository notificationClaimRepository;
    private final NotificationRepository notificationRepository;
    private final DeliverNotificationPort deliverNotificationPort;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final ExecutorService executor;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    public DeliveryWorker(
            NotificationClaimRepository notificationClaimRepository,
            NotificationRepository notificationRepository,
            DeliverNotificationPort deliverNotificationPort,
            ConcurrencyLimiter concurrencyLimiter,
            @Value("${worker.batch-size}") int batchSize,
            @Value("${worker.lease-duration}") Duration leaseDuration) {
        this.notificationClaimRepository =
                Objects.requireNonNull(notificationClaimRepository, "notificationClaimRepository must not be null");
        this.notificationRepository =
                Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.deliverNotificationPort =
                Objects.requireNonNull(deliverNotificationPort, "deliverNotificationPort must not be null");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter must not be null");
        this.batchSize = batchSize;
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        this.workerId = UUID.randomUUID().toString();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval}")
    public void pollAndDispatch() {
        int availableCapacity = concurrencyLimiter.availableGlobalPermits();
        if (availableCapacity <= 0) {
            log.info("Skipping poll cycle: no available global delivery capacity");
            return;
        }

        int limit = Math.min(batchSize, availableCapacity);
        List<UUID> claimed = notificationClaimRepository.claimDueBatch(limit, workerId, leaseDuration);
        for (UUID notificationId : claimed) {
            executor.submit(() -> processOne(notificationId));
        }
    }

    void processOne(UUID notificationId) {
        String clientId = notificationRepository.findById(notificationId)
                .map(Notification::getClientId)
                .orElse(null);
        if (clientId == null) {
            log.warn("Claimed notification {} could not be found for client resolution; skipping", notificationId);
            return;
        }

        try {
            concurrencyLimiter.acquire(clientId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            deliverNotificationPort.deliver(notificationId);
        } catch (Exception e) {
            log.error("Delivery failed for notification {}", notificationId, e);
        } finally {
            concurrencyLimiter.release(clientId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}
