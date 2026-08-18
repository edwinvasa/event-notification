package com.edwin.eventnotification.domain.delivery;

import java.time.Duration;
import java.util.Objects;

public record DeliveryOutcome(
        DeliveryOutcomeType outcomeType,
        Integer httpStatusCode,
        long durationMillis,
        String errorDetail,
        String responseSnippet,
        Duration retryAfter) {

    public DeliveryOutcome {
        Objects.requireNonNull(outcomeType, "outcomeType must not be null");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
    }
}
