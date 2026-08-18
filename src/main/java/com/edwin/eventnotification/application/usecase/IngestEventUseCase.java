package com.edwin.eventnotification.application.usecase;

import java.util.List;
import java.util.Objects;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.domain.event.Event;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationFactory;
import com.edwin.eventnotification.domain.subscription.Subscription;

public class IngestEventUseCase implements IngestEventPort {

    private final SubscriptionPort subscriptionPort;
    private final NotificationRepository notificationRepository;
    private final NotificationFactory notificationFactory;

    public IngestEventUseCase(
            SubscriptionPort subscriptionPort,
            NotificationRepository notificationRepository,
            NotificationFactory notificationFactory) {
        this.subscriptionPort = Objects.requireNonNull(subscriptionPort, "subscriptionPort must not be null");
        this.notificationRepository =
                Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.notificationFactory = Objects.requireNonNull(notificationFactory, "notificationFactory must not be null");
    }

    @Override
    public void ingest(Event event) {
        List<Subscription> subscriptions =
                subscriptionPort.findActiveSubscriptions(event.clientId(), event.eventType());

        for (Subscription subscription : subscriptions) {
            Notification notification = notificationFactory.create(event, subscription);
            notificationRepository.saveIdempotent(notification);
        }
    }
}
