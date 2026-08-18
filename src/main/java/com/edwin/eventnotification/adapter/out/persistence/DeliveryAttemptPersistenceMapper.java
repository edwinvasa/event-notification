package com.edwin.eventnotification.adapter.out.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;

@Component
public class DeliveryAttemptPersistenceMapper {

    public DeliveryAttemptJpaEntity toEntity(DeliveryAttempt attempt, UUID notificationId) {
        return new DeliveryAttemptJpaEntity(
                attempt.id(),
                notificationId,
                attempt.attemptNumber(),
                attempt.occurredAt(),
                attempt.duration(),
                attempt.outcomeType(),
                attempt.httpStatusCode(),
                attempt.errorDetail(),
                attempt.responseSnippet(),
                attempt.urlUsed(),
                attempt.trigger());
    }
}
