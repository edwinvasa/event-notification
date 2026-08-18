package com.edwin.eventnotification.application.exception;

import java.util.UUID;

/**
 * Thrown when a notification exists and belongs to the requesting client, but is not currently in
 * a state that can be replayed (not FAILED, or lost a concurrent replay race - see ADR-008 §2).
 */
public class NotificationNotReplayableException extends RuntimeException {

    private final UUID notificationId;

    public NotificationNotReplayableException(UUID notificationId) {
        super("Notification " + notificationId + " is not currently replayable");
        this.notificationId = notificationId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }
}
