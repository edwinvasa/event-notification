package com.edwin.eventnotification.domain.event;

import java.time.Instant;
import java.util.Objects;

public record Event(
        String eventId,
        String eventType,
        String clientId,
        String payload,
        Instant occurredAt) {

    public Event {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
