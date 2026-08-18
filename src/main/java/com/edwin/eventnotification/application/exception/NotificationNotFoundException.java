package com.edwin.eventnotification.application.exception;

import java.util.UUID;

/**
 * Thrown when a notification does not exist, or exists but does not belong to the requesting
 * client. Both cases must produce this exact exception so a driving adapter can map them to the
 * same response and never reveal whether the notification exists for another client (BOLA
 * protection per ADR-008).
 */
public class NotificationNotFoundException extends RuntimeException {

    private final UUID notificationId;

    public NotificationNotFoundException(UUID notificationId) {
        super("Notification " + notificationId + " not found");
        this.notificationId = notificationId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }
}
