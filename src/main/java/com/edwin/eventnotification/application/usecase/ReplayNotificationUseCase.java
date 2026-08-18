package com.edwin.eventnotification.application.usecase;

import java.util.Objects;
import java.util.UUID;

import com.edwin.eventnotification.application.exception.NotificationNotFoundException;
import com.edwin.eventnotification.application.exception.NotificationNotReplayableException;
import com.edwin.eventnotification.application.port.in.ReplayNotificationPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.result.ReplayResult;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

public class ReplayNotificationUseCase implements ReplayNotificationPort {

    private final NotificationRepository notificationRepository;

    public ReplayNotificationUseCase(NotificationRepository notificationRepository) {
        this.notificationRepository =
                Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
    }

    @Override
    public ReplayResult replay(UUID notificationId, String clientId) {
        Notification notification = notificationRepository
                .findByIdAndClientId(notificationId, clientId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new NotificationNotReplayableException(notificationId);
        }

        boolean transitioned = notificationRepository.transitionFailedToPending(notificationId);
        if (!transitioned) {
            throw new NotificationNotReplayableException(notificationId);
        }

        notification.replay();

        return new ReplayResult(
                notification.getId(),
                notification.getStatus(),
                notification.getAttemptCount(),
                notification.getLastAttemptedAt());
    }
}
