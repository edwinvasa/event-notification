package com.edwin.eventnotification.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.application.result.ReplayResult;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

public record ReplayResponse(UUID notificationId, NotificationStatus status, int attemptCount, Instant lastAttemptedAt) {

    public static ReplayResponse from(ReplayResult result) {
        return new ReplayResponse(
                result.notificationId(), result.status(), result.attemptCount(), result.lastAttemptedAt());
    }
}
