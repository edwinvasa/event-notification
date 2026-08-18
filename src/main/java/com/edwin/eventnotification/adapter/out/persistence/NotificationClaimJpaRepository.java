package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationClaimJpaRepository extends JpaRepository<NotificationJpaEntity, UUID> {

    @Query(
            value = """
            WITH candidates AS (
                SELECT id FROM notifications
                WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                   OR (status = 'PROCESSING' AND lease_expires_at < :now)
                ORDER BY COALESCE(next_attempt_at, event_occurred_at)
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notifications n
            SET status = 'PROCESSING', claimed_by = :workerId, lease_expires_at = :leaseExpiresAt
            FROM candidates c
            WHERE n.id = c.id
            RETURNING n.id
            """,
            nativeQuery = true)
    List<UUID> claimDueBatch(
            @Param("now") Instant now,
            @Param("limit") int limit,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);
}
