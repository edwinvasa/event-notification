package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

public interface NotificationJpaRepository extends JpaRepository<NotificationJpaEntity, UUID> {

    @Modifying
    @Query(
            value = """
            INSERT INTO notifications
                (id, event_id, subscription_id, client_id, event_occurred_at, payload,
                 status, attempt_count, next_attempt_at, last_attempted_at,
                 failure_reason, claimed_by, lease_expires_at)
            VALUES
                (:id, :eventId, :subscriptionId, :clientId, :eventOccurredAt, :payload,
                 :status, :attemptCount, :nextAttemptAt, :lastAttemptedAt,
                 :failureReason, :claimedBy, :leaseExpiresAt)
            ON CONFLICT (event_id, subscription_id) DO NOTHING
            """,
            nativeQuery = true)
    void insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventId") String eventId,
            @Param("subscriptionId") UUID subscriptionId,
            @Param("clientId") String clientId,
            @Param("eventOccurredAt") Instant eventOccurredAt,
            @Param("payload") String payload,
            @Param("status") String status,
            @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastAttemptedAt") Instant lastAttemptedAt,
            @Param("failureReason") String failureReason,
            @Param("claimedBy") String claimedBy,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    Optional<NotificationJpaEntity> findByIdAndClientId(UUID id, String clientId);

    /**
     * Backlog signal: notifications ready to be claimed right now (PENDING and due). Backs the
     * {@code notification.backlog.pending} gauge - an operational/metrics query, not a business
     * capability, so it is not exposed through the application-facing NotificationRepository port.
     */
    @Query("""
            SELECT COUNT(n) FROM NotificationJpaEntity n
            WHERE n.status = com.edwin.eventnotification.domain.notification.NotificationStatus.PENDING
              AND (n.nextAttemptAt IS NULL OR n.nextAttemptAt <= :now)
            """)
    long countDuePending(@Param("now") Instant now);

    @Query("""
            SELECT n FROM NotificationJpaEntity n
            WHERE n.clientId = :clientId
              AND n.eventOccurredAt >= COALESCE(:createdFrom, n.eventOccurredAt)
              AND n.eventOccurredAt <= COALESCE(:createdTo, n.eventOccurredAt)
              AND n.status = COALESCE(:status, n.status)
            ORDER BY n.eventOccurredAt DESC
            """)
    List<NotificationJpaEntity> findByClientId(
            @Param("clientId") String clientId,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("status") NotificationStatus status);

    @Modifying
    @Query("""
            UPDATE NotificationJpaEntity n
            SET n.status = com.edwin.eventnotification.domain.notification.NotificationStatus.PENDING,
                n.nextAttemptAt = null,
                n.failureReason = null
            WHERE n.id = :notificationId
              AND n.status = com.edwin.eventnotification.domain.notification.NotificationStatus.FAILED
            """)
    int transitionFailedToPending(@Param("notificationId") UUID notificationId);

    @Modifying
    @Query("""
            UPDATE NotificationJpaEntity n
            SET n.status = :status,
                n.attemptCount = :attemptCount,
                n.nextAttemptAt = :nextAttemptAt,
                n.lastAttemptedAt = :lastAttemptedAt,
                n.failureReason = :failureReason,
                n.claimedBy = :claimedBy,
                n.leaseExpiresAt = :leaseExpiresAt
            WHERE n.id = :id
              AND n.claimedBy = :expectedClaimedBy
              AND n.leaseExpiresAt = :expectedLeaseExpiresAt
            """)
    int recordDeliveryResultIfClaimStillValid(
            @Param("id") UUID id,
            @Param("status") NotificationStatus status,
            @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastAttemptedAt") Instant lastAttemptedAt,
            @Param("failureReason") FailureReason failureReason,
            @Param("claimedBy") String claimedBy,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("expectedClaimedBy") String expectedClaimedBy,
            @Param("expectedLeaseExpiresAt") Instant expectedLeaseExpiresAt);
}
