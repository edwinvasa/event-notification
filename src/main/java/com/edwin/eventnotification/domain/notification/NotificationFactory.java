package com.edwin.eventnotification.domain.notification;

import java.util.UUID;

import com.edwin.eventnotification.domain.event.Event;
import com.edwin.eventnotification.domain.subscription.Subscription;

public class NotificationFactory {

    public Notification create(Event event, Subscription subscription) {
        return Notification.create(
                UUID.randomUUID(),
                event.eventId(),
                subscription.id(),
                subscription.clientId(),
                event.occurredAt(),
                event.payload());
    }
}
