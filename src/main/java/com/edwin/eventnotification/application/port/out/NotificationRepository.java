package com.edwin.eventnotification.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.notification.Notification;

public interface NotificationRepository {

    void saveIdempotent(Notification notification);

    /**
     * Persists the delivery result only if the notification's claim is still the one identified
     * by {@code expectedClaimedBy}/{@code expectedLeaseExpiresAt} (the claim token captured
     * before the notification's state was mutated). If the claim was superseded by a later
     * reclaim, neither the notification nor the delivery attempt are persisted.
     */
    void recordDeliveryResult(
            Notification notification,
            DeliveryAttempt attempt,
            String expectedClaimedBy,
            Instant expectedLeaseExpiresAt);

    boolean transitionFailedToPending(UUID notificationId);

    Optional<Notification> findById(UUID notificationId);

    /**
     * Scopes the lookup to a specific client_id at the query level, for callers that already know
     * which client is asking (REST-facing use cases) so BOLA protection (ADR-008 §1) does not rely
     * on loading the row and filtering in application code.
     */
    Optional<Notification> findByIdAndClientId(UUID notificationId, String clientId);

    List<Notification> findByClientId(String clientId, NotificationQueryFilter filter);
}
