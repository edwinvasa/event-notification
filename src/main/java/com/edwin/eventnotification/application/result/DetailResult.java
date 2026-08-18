package com.edwin.eventnotification.application.result;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

public record DetailResult(
        UUID notificationId,
        String eventId,
        NotificationStatus status,
        int attemptCount,
        Instant eventOccurredAt,
        Instant lastAttemptedAt,
        Instant nextAttemptAt,
        FailureReason failureReason,
        String payload) {
}
