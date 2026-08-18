package com.edwin.eventnotification.application.port.out;

import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.notification.FailureReason;

/**
 * Records delivery observability signals. Uses only domain types so the application layer stays
 * free of any metrics library (Micrometer, etc.) - the adapter implementing this is the only place
 * that knows about the actual metrics backend.
 */
public interface DeliveryMetricsPort {

    void recordDeliveryAttempt(DeliveryOutcomeType outcomeType, long durationMillis);

    void recordRetryScheduled();

    void recordDefinitiveFailure(FailureReason reason);
}
