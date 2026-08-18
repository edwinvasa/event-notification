package com.edwin.eventnotification.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.application.result.DetailResult;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

public record NotificationDetailResponse(
        UUID notificationId,
        String eventId,
        NotificationStatus status,
        int attemptCount,
        Instant eventOccurredAt,
        Instant lastAttemptedAt,
        Instant nextAttemptAt,
        FailureReason failureReason,
        String payload) {

    public static NotificationDetailResponse from(DetailResult result) {
        return new NotificationDetailResponse(
                result.notificationId(),
                result.eventId(),
                result.status(),
                result.attemptCount(),
                result.eventOccurredAt(),
                result.lastAttemptedAt(),
                result.nextAttemptAt(),
                result.failureReason(),
                result.payload());
    }
}
