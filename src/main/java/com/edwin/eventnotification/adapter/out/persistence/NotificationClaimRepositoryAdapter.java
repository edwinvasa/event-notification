package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;

@Repository
public class NotificationClaimRepositoryAdapter implements NotificationClaimRepository {

    private final NotificationClaimJpaRepository notificationClaimJpaRepository;
    private final Clock clock;

    public NotificationClaimRepositoryAdapter(
            NotificationClaimJpaRepository notificationClaimJpaRepository, Clock clock) {
        this.notificationClaimJpaRepository = notificationClaimJpaRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<UUID> claimDueBatch(int limit, String workerId, Duration leaseDuration) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plus(leaseDuration);
        return notificationClaimJpaRepository.claimDueBatch(now, limit, workerId, leaseExpiresAt);
    }
}
