package com.edwin.eventnotification.adapter.out.persistence;

import org.springframework.stereotype.Component;

import com.edwin.eventnotification.domain.notification.Notification;

@Component
public class NotificationPersistenceMapper {

    public Notification toDomain(NotificationJpaEntity entity) {
        return Notification.restore(
                entity.getId(),
                entity.getEventId(),
                entity.getSubscriptionId(),
                entity.getClientId(),
                entity.getEventOccurredAt(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getLastAttemptedAt(),
                entity.getFailureReason(),
                entity.getClaimedBy(),
                entity.getLeaseExpiresAt());
    }
}
