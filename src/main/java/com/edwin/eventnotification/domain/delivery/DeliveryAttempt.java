package com.edwin.eventnotification.domain.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        int attemptNumber,
        Instant occurredAt,
        Duration duration,
        DeliveryOutcomeType outcomeType,
        Integer httpStatusCode,
        String errorDetail,
        String responseSnippet,
        String urlUsed,
        Trigger trigger) {

    public DeliveryAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(outcomeType, "outcomeType must not be null");
        Objects.requireNonNull(urlUsed, "urlUsed must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }
}
