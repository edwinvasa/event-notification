package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.delivery.Trigger;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationRepositoryAdapterFencingIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationClaimRepository notificationClaimRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    @Autowired
    private DeliveryAttemptJpaRepository deliveryAttemptJpaRepository;

    private UUID notificationId;
    private final List<UUID> createdAttemptIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdAttemptIds.forEach(deliveryAttemptJpaRepository::deleteById);
        createdAttemptIds.clear();
        if (notificationId != null) {
            notificationJpaRepository.deleteById(notificationId);
        }
    }

    private UUID newEligibleNotification() {
        notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notificationRepository.saveIdempotent(notification);
        return notificationId;
    }

    private DeliveryAttempt successAttempt(UUID attemptId, int attemptNumber) {
        createdAttemptIds.add(attemptId);
        return new DeliveryAttempt(
                attemptId,
                attemptNumber,
                Instant.now(),
                Duration.ofMillis(120),
                DeliveryOutcomeType.SUCCESS,
                200,
                null,
                null,
                "https://example.com/webhook",
                Trigger.AUTOMATIC);
    }

    @Test
    void staleWorkerCannotOverwriteTheResultOfALaterReclaim() {
        newEligibleNotification();

        // Worker A claims with an already-expired lease, simulating that its HTTP call is going
        // to take far longer than its own lease.
        List<UUID> claimedByA = notificationClaimRepository.claimDueBatch(10, "worker-A", Duration.ofSeconds(-1));
        assertThat(claimedByA).containsExactly(notificationId);

        Notification asSeenByA = notificationRepository.findById(notificationId).orElseThrow();
        String expectedClaimedByA = asSeenByA.getClaimedBy();
        Instant expectedLeaseExpiresAtA = asSeenByA.getLeaseExpiresAt();
        assertThat(expectedClaimedByA).isEqualTo("worker-A");

        // Worker B reclaims: A's lease is already in the past, so the claim query picks it up.
        List<UUID> claimedByB = notificationClaimRepository.claimDueBatch(10, "worker-B", Duration.ofSeconds(60));
        assertThat(claimedByB).containsExactly(notificationId);

        // B finishes first and persists its (successful) result.
        Notification asSeenByB = notificationRepository.findById(notificationId).orElseThrow();
        String expectedClaimedByB = asSeenByB.getClaimedBy();
        Instant expectedLeaseExpiresAtB = asSeenByB.getLeaseExpiresAt();
        assertThat(expectedClaimedByB).isEqualTo("worker-B");
        asSeenByB.recordSuccess(Instant.now());
        UUID attemptIdB = UUID.randomUUID();
        notificationRepository.recordDeliveryResult(
                asSeenByB, successAttempt(attemptIdB, 1), expectedClaimedByB, expectedLeaseExpiresAtB);

        Notification afterB = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(afterB.getStatus()).isEqualTo(NotificationStatus.COMPLETED);

        // A finally finishes its stale HTTP call and tries to persist using its original,
        // now-superseded token.
        asSeenByA.recordDefinitiveFailure(Instant.now(), FailureReason.PERMANENT_ERROR);
        UUID attemptIdA = UUID.randomUUID();
        DeliveryAttempt staleAttemptA = new DeliveryAttempt(
                attemptIdA,
                1,
                Instant.now(),
                Duration.ofMillis(120),
                DeliveryOutcomeType.HTTP_ERROR,
                500,
                "boom",
                null,
                "https://example.com/webhook",
                Trigger.AUTOMATIC);
        notificationRepository.recordDeliveryResult(
                asSeenByA, staleAttemptA, expectedClaimedByA, expectedLeaseExpiresAtA);

        // B's result remains the sole, authoritative outcome; A's stale write and its
        // DeliveryAttempt never made it in.
        Notification finalState = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(NotificationStatus.COMPLETED);
        assertThat(deliveryAttemptJpaRepository.findById(attemptIdB)).isPresent();
        assertThat(deliveryAttemptJpaRepository.findById(attemptIdA)).isEmpty();
    }

    @Test
    void recordDeliveryResultSucceedsWhenTheClaimTokenStillMatches() {
        newEligibleNotification();
        notificationClaimRepository.claimDueBatch(10, "worker-1", Duration.ofSeconds(60));

        Notification notification = notificationRepository.findById(notificationId).orElseThrow();
        String expectedClaimedBy = notification.getClaimedBy();
        Instant expectedLeaseExpiresAt = notification.getLeaseExpiresAt();
        notification.recordSuccess(Instant.now());

        UUID attemptId = UUID.randomUUID();
        notificationRepository.recordDeliveryResult(
                notification, successAttempt(attemptId, 1), expectedClaimedBy, expectedLeaseExpiresAt);

        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.COMPLETED);
        assertThat(persisted.getClaimedBy()).isNull();
        assertThat(persisted.getLeaseExpiresAt()).isNull();
        assertThat(deliveryAttemptJpaRepository.findById(attemptId)).isPresent();
    }

    @Test
    void onlyOneOfTwoConcurrentWritesSharingTheSameClaimTokenSucceeds() throws Exception {
        newEligibleNotification();
        notificationClaimRepository.claimDueBatch(10, "worker-1", Duration.ofSeconds(60));

        Notification base = notificationRepository.findById(notificationId).orElseThrow();
        String expectedClaimedBy = base.getClaimedBy();
        Instant expectedLeaseExpiresAt = base.getLeaseExpiresAt();

        UUID attemptId1 = UUID.randomUUID();
        UUID attemptId2 = UUID.randomUUID();
        // Built on the main thread before the race starts: successAttempt() mutates the shared
        // createdAttemptIds list, which is not safe to call concurrently from the two writers.
        DeliveryAttempt attempt1 = successAttempt(attemptId1, 1);
        DeliveryAttempt attempt2 = successAttempt(attemptId2, 1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Void> writer1 = () -> {
            ready.countDown();
            go.await();
            Notification n = notificationRepository.findById(notificationId).orElseThrow();
            n.recordSuccess(Instant.now());
            notificationRepository.recordDeliveryResult(n, attempt1, expectedClaimedBy, expectedLeaseExpiresAt);
            return null;
        };
        Callable<Void> writer2 = () -> {
            ready.countDown();
            go.await();
            Notification n = notificationRepository.findById(notificationId).orElseThrow();
            n.recordSuccess(Instant.now());
            notificationRepository.recordDeliveryResult(n, attempt2, expectedClaimedBy, expectedLeaseExpiresAt);
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> f1 = executor.submit(writer1);
            Future<Void> f2 = executor.submit(writer2);
            ready.await();
            go.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        boolean attempt1Persisted = deliveryAttemptJpaRepository.findById(attemptId1).isPresent();
        boolean attempt2Persisted = deliveryAttemptJpaRepository.findById(attemptId2).isPresent();

        assertThat(attempt1Persisted ^ attempt2Persisted)
                .as("exactly one of the two concurrent writes should have won")
                .isTrue();

        Notification finalState = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(NotificationStatus.COMPLETED);
    }
}
