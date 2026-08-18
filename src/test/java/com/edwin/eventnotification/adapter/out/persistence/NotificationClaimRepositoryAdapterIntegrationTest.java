package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

/**
 * claimDueBatch() operates on the whole notifications table (by design - it is not scoped by
 * client), so every test here needs the table to contain exactly its own fixtures. Stray PENDING
 * rows left behind by another process (e.g. the manual demo seed/ingestion tests, or a previous
 * failed run) would otherwise silently inflate or starve out a claim, so the table is wiped before
 * every test rather than relying only on each test cleaning up after itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationClaimRepositoryAdapterIntegrationTest {

    @Autowired
    private NotificationClaimRepository notificationClaimRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    @Autowired
    private DeliveryAttemptJpaRepository deliveryAttemptJpaRepository;

    private final List<UUID> createdNotificationIds = new ArrayList<>();

    @BeforeEach
    void ensureCleanSlate() {
        deliveryAttemptJpaRepository.deleteAll();
        notificationJpaRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        createdNotificationIds.forEach(notificationJpaRepository::deleteById);
        createdNotificationIds.clear();
    }

    private Notification newEligiblePending() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.create(
                id, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        createdNotificationIds.add(id);
        notificationRepository.saveIdempotent(notification);
        return notification;
    }

    private Notification newNotDueYet() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.create(
                id, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notification.claim("seed-worker", Instant.now().plusSeconds(60));
        notification.recordRetryableFailure(Instant.now(), Instant.now().plusSeconds(3600));
        createdNotificationIds.add(id);
        notificationRepository.saveIdempotent(notification);
        return notification;
    }

    private Notification newExpiredLease() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.create(
                id, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notification.claim("stale-worker", Instant.now().minusSeconds(120));
        createdNotificationIds.add(id);
        notificationRepository.saveIdempotent(notification);
        return notification;
    }

    @Test
    void claimsEligiblePendingNotification() {
        Notification eligible = newEligiblePending();

        List<UUID> claimed = notificationClaimRepository.claimDueBatch(10, "worker-1", Duration.ofMinutes(1));

        assertThat(claimed).contains(eligible.getId());
        Notification persisted = notificationRepository.findById(eligible.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(persisted.getClaimedBy()).isEqualTo("worker-1");
        assertThat(persisted.getLeaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void doesNotClaimNotificationThatIsNotDueYet() {
        Notification notDue = newNotDueYet();

        List<UUID> claimed = notificationClaimRepository.claimDueBatch(10, "worker-1", Duration.ofMinutes(1));

        assertThat(claimed).doesNotContain(notDue.getId());
        Notification persisted = notificationRepository.findById(notDue.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void claimsNotificationWithExpiredLease() {
        Notification expired = newExpiredLease();

        List<UUID> claimed = notificationClaimRepository.claimDueBatch(10, "worker-2", Duration.ofMinutes(1));

        assertThat(claimed).contains(expired.getId());
        Notification persisted = notificationRepository.findById(expired.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(persisted.getClaimedBy()).isEqualTo("worker-2");
    }

    @Test
    void respectsLimitWhenClaimingBatch() {
        IntStream.range(0, 5).forEach(i -> newEligiblePending());

        List<UUID> claimed = notificationClaimRepository.claimDueBatch(3, "worker-1", Duration.ofMinutes(1));

        assertThat(claimed).hasSize(3);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameNotification() throws Exception {
        IntStream.range(0, 10).mapToObj(i -> newEligiblePending()).collect(Collectors.toList());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<List<UUID>> workerOneTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return notificationClaimRepository.claimDueBatch(10, "worker-A", Duration.ofMinutes(1));
        };
        Callable<List<UUID>> workerTwoTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return notificationClaimRepository.claimDueBatch(10, "worker-B", Duration.ofMinutes(1));
        };

        try {
            Future<List<UUID>> futureOne = executor.submit(workerOneTask);
            Future<List<UUID>> futureTwo = executor.submit(workerTwoTask);

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<UUID> claimedByOne = futureOne.get(10, TimeUnit.SECONDS);
            List<UUID> claimedByTwo = futureTwo.get(10, TimeUnit.SECONDS);

            Set<UUID> overlap = new HashSet<>(claimedByOne);
            overlap.retainAll(claimedByTwo);
            assertThat(overlap).isEmpty();

            Set<UUID> allClaimed = new HashSet<>();
            allClaimed.addAll(claimedByOne);
            allClaimed.addAll(claimedByTwo);
            assertThat(allClaimed).hasSize(10);
        } finally {
            executor.shutdown();
        }
    }
}
