package com.edwin.eventnotification.application.usecase;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.edwin.eventnotification.application.exception.NotificationNotFoundException;
import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.in.NotificationQueryPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.result.DetailResult;
import com.edwin.eventnotification.domain.notification.Notification;

public class NotificationQueryUseCase implements NotificationQueryPort {

    private final NotificationRepository notificationRepository;

    public NotificationQueryUseCase(NotificationRepository notificationRepository) {
        this.notificationRepository =
                Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
    }

    @Override
    public List<Notification> list(String clientId, NotificationQueryFilter filter) {
        return notificationRepository.findByClientId(clientId, filter);
    }

    @Override
    public DetailResult getDetail(UUID notificationId, String clientId) {
        Notification notification = notificationRepository
                .findByIdAndClientId(notificationId, clientId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        return new DetailResult(
                notification.getId(),
                notification.getEventId(),
                notification.getStatus(),
                notification.getAttemptCount(),
                notification.getEventOccurredAt(),
                notification.getLastAttemptedAt(),
                notification.getNextAttemptAt(),
                notification.getFailureReason(),
                notification.getPayload());
    }
}
