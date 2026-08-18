package com.edwin.eventnotification.application.port;

import java.time.Instant;

import com.edwin.eventnotification.domain.notification.NotificationStatus;

public record NotificationQueryFilter(
        Instant createdFrom,
        Instant createdTo,
        NotificationStatus status) {
}
