package com.edwin.eventnotification.application.result;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.domain.notification.NotificationStatus;

public record ReplayResult(UUID notificationId, NotificationStatus status, int attemptCount, Instant lastAttemptedAt) {
}
