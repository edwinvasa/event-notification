package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.notification.Notification;

@Repository
public class NotificationRepositoryAdapter implements NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepositoryAdapter.class);

    private final NotificationJpaRepository notificationJpaRepository;
    private final NotificationPersistenceMapper mapper;
    private final DeliveryAttemptJpaRepository deliveryAttemptJpaRepository;
    private final DeliveryAttemptPersistenceMapper deliveryAttemptPersistenceMapper;

    public NotificationRepositoryAdapter(
            NotificationJpaRepository notificationJpaRepository,
            NotificationPersistenceMapper mapper,
            DeliveryAttemptJpaRepository deliveryAttemptJpaRepository,
            DeliveryAttemptPersistenceMapper deliveryAttemptPersistenceMapper) {
        this.notificationJpaRepository = notificationJpaRepository;
        this.mapper = mapper;
        this.deliveryAttemptJpaRepository = deliveryAttemptJpaRepository;
        this.deliveryAttemptPersistenceMapper = deliveryAttemptPersistenceMapper;
    }

    @Override
    @Transactional
    public void saveIdempotent(Notification notification) {
        notificationJpaRepository.insertIfAbsent(
                notification.getId(),
                notification.getEventId(),
                notification.getSubscriptionId(),
                notification.getClientId(),
                notification.getEventOccurredAt(),
                notification.getPayload(),
                notification.getStatus().name(),
                notification.getAttemptCount(),
                notification.getNextAttemptAt(),
                notification.getLastAttemptedAt(),
                notification.getFailureReason() != null ? notification.getFailureReason().name() : null,
                notification.getClaimedBy(),
                notification.getLeaseExpiresAt());
    }

    @Override
    @Transactional
    public void recordDeliveryResult(
            Notification notification,
            DeliveryAttempt attempt,
            String expectedClaimedBy,
            Instant expectedLeaseExpiresAt) {
        int rowsAffected = notificationJpaRepository.recordDeliveryResultIfClaimStillValid(
                notification.getId(),
                notification.getStatus(),
                notification.getAttemptCount(),
                notification.getNextAttemptAt(),
                notification.getLastAttemptedAt(),
                notification.getFailureReason(),
                notification.getClaimedBy(),
                notification.getLeaseExpiresAt(),
                expectedClaimedBy,
                expectedLeaseExpiresAt);

        if (rowsAffected == 0) {
            log.warn(
                    "Discarding delivery result for notification {}: claim token (claimedBy={}, "
                            + "leaseExpiresAt={}) is no longer valid, the notification was reclaimed",
                    notification.getId(),
                    expectedClaimedBy,
                    expectedLeaseExpiresAt);
            return;
        }

        DeliveryAttemptJpaEntity deliveryAttemptEntity =
                deliveryAttemptPersistenceMapper.toEntity(attempt, notification.getId());
        deliveryAttemptJpaRepository.save(deliveryAttemptEntity);
    }

    @Override
    @Transactional
    public boolean transitionFailedToPending(UUID notificationId) {
        int rowsAffected = notificationJpaRepository.transitionFailedToPending(notificationId);
        return rowsAffected > 0;
    }

    @Override
    public Optional<Notification> findById(UUID notificationId) {
        return notificationJpaRepository.findById(notificationId).map(mapper::toDomain);
    }

    @Override
    public Optional<Notification> findByIdAndClientId(UUID notificationId, String clientId) {
        return notificationJpaRepository.findByIdAndClientId(notificationId, clientId).map(mapper::toDomain);
    }

    @Override
    public List<Notification> findByClientId(String clientId, NotificationQueryFilter filter) {
        return notificationJpaRepository
                .findByClientId(clientId, filter.createdFrom(), filter.createdTo(), filter.status())
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
