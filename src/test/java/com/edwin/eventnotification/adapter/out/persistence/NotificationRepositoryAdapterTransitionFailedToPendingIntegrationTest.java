package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationRepositoryAdapterTransitionFailedToPendingIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    private UUID notificationId;

    @AfterEach
    void cleanUp() {
        if (notificationId != null) {
            notificationJpaRepository.deleteById(notificationId);
        }
    }

    private Notification failedNotification() {
        notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId,
                "evt-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "client-1",
                Instant.now(),
                "payload");
        notification.claim("test-worker", Instant.now().plusSeconds(60));
        notification.recordDefinitiveFailure(Instant.now(), FailureReason.PERMANENT_ERROR);
        return notification;
    }

    @Test
    void transitionsFailedNotificationToPendingAndReturnsTrue() {
        Notification notification = failedNotification();
        notificationRepository.saveIdempotent(notification);

        boolean result = notificationRepository.transitionFailedToPending(notificationId);

        assertThat(result).isTrue();
        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void returnsFalseAndLeavesStateUnchangedWhenNotFailed() {
        notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId,
                "evt-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "client-1",
                Instant.now(),
                "payload");
        notificationRepository.saveIdempotent(notification);

        boolean result = notificationRepository.transitionFailedToPending(notificationId);

        assertThat(result).isFalse();
        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void exactlyOneOfTwoConcurrentTransitionsWins() throws Exception {
        Notification notification = failedNotification();
        notificationRepository.saveIdempotent(notification);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Boolean> attempt = () -> {
            readyLatch.countDown();
            startLatch.await();
            return notificationRepository.transitionFailedToPending(notificationId);
        };

        try {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            boolean firstResult = first.get(10, TimeUnit.SECONDS);
            boolean secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResult, secondResult)).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdown();
        }

        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }
}
