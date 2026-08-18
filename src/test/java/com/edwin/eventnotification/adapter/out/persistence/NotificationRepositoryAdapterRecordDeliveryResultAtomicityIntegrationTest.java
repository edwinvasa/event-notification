package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.delivery.Trigger;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationRepositoryAdapterRecordDeliveryResultAtomicityIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationClaimRepository notificationClaimRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    @Autowired
    private DeliveryAttemptJpaRepository deliveryAttemptJpaRepository;

    private UUID notificationId;
    private UUID attemptId;

    @AfterEach
    void cleanUp() {
        if (attemptId != null) {
            deliveryAttemptJpaRepository.deleteById(attemptId);
        }
        if (notificationId != null) {
            notificationJpaRepository.deleteById(notificationId);
        }
    }

    @Test
    void recordDeliveryResultRollsBackBothWritesWhenDeliveryAttemptPersistenceFails() {
        notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId,
                "evt-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "client-1",
                Instant.now(),
                "payload");
        notificationRepository.saveIdempotent(notification);

        List<UUID> claimed = notificationClaimRepository.claimDueBatch(10, "test-worker", Duration.ofSeconds(60));
        assertThat(claimed).containsExactly(notificationId);

        Notification claimedNotification = notificationRepository.findById(notificationId).orElseThrow();
        String claimedBy = claimedNotification.getClaimedBy();
        Instant leaseExpiresAt = claimedNotification.getLeaseExpiresAt();
        claimedNotification.recordSuccess(Instant.now());

        attemptId = UUID.randomUUID();
        DeliveryAttempt attemptWithOversizedDuration = new DeliveryAttempt(
                attemptId,
                1,
                Instant.now(),
                Duration.ofSeconds(Long.MAX_VALUE),
                DeliveryOutcomeType.SUCCESS,
                200,
                null,
                null,
                "https://example.com/webhook",
                Trigger.AUTOMATIC);

        assertThatThrownBy(() -> notificationRepository.recordDeliveryResult(
                        claimedNotification, attemptWithOversizedDuration, claimedBy, leaseExpiresAt))
                .isInstanceOf(DataAccessException.class);

        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PROCESSING);

        assertThat(deliveryAttemptJpaRepository.findById(attemptId)).isEmpty();
    }
}
