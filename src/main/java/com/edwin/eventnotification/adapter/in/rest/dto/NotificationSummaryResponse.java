package com.edwin.eventnotification.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

/**
 * List-item view of a Notification. Deliberately excludes the event payload (ADR-008 §7: "el
 * payload completo no se expondrá en endpoints de listado") and any internal claim/lease fields.
 */
public record NotificationSummaryResponse(
        UUID notificationId,
        String eventId,
        NotificationStatus status,
        int attemptCount,
        Instant eventOccurredAt,
        Instant lastAttemptedAt,
        Instant nextAttemptAt,
        FailureReason failureReason) {

    public static NotificationSummaryResponse from(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getId(),
                notification.getEventId(),
                notification.getStatus(),
                notification.getAttemptCount(),
                notification.getEventOccurredAt(),
                notification.getLastAttemptedAt(),
                notification.getNextAttemptAt(),
                notification.getFailureReason());
    }
}
