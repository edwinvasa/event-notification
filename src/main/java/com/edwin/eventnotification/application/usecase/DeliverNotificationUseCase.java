package com.edwin.eventnotification.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

import com.edwin.eventnotification.application.port.in.DeliverNotificationPort;
import com.edwin.eventnotification.application.port.out.DeliveryMetricsPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.application.port.out.WebhookDeliveryRequest;
import com.edwin.eventnotification.application.port.out.WebhookSenderPort;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.delivery.DeliveryErrorClassifier;
import com.edwin.eventnotification.domain.delivery.DeliveryErrorClassifier.Classification;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.delivery.RetryPolicy;
import com.edwin.eventnotification.domain.delivery.Trigger;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.subscription.Subscription;

public class DeliverNotificationUseCase implements DeliverNotificationPort {

    private final NotificationRepository notificationRepository;
    private final SubscriptionPort subscriptionPort;
    private final WebhookSenderPort webhookSenderPort;
    private final DeliveryErrorClassifier deliveryErrorClassifier;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final RandomGenerator randomGenerator;
    private final DeliveryMetricsPort deliveryMetricsPort;
    private final int maxAttempts;

    public DeliverNotificationUseCase(
            NotificationRepository notificationRepository,
            SubscriptionPort subscriptionPort,
            WebhookSenderPort webhookSenderPort,
            DeliveryErrorClassifier deliveryErrorClassifier,
            RetryPolicy retryPolicy,
            Clock clock,
            RandomGenerator randomGenerator,
            DeliveryMetricsPort deliveryMetricsPort,
            int maxAttempts) {
        this.notificationRepository =
                Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.subscriptionPort = Objects.requireNonNull(subscriptionPort, "subscriptionPort must not be null");
        this.webhookSenderPort = Objects.requireNonNull(webhookSenderPort, "webhookSenderPort must not be null");
        this.deliveryErrorClassifier =
                Objects.requireNonNull(deliveryErrorClassifier, "deliveryErrorClassifier must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.randomGenerator = Objects.requireNonNull(randomGenerator, "randomGenerator must not be null");
        this.deliveryMetricsPort =
                Objects.requireNonNull(deliveryMetricsPort, "deliveryMetricsPort must not be null");
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void deliver(UUID notificationId) {
        Notification notification = notificationRepository
                .findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("Notification " + notificationId + " not found"));

        String expectedClaimedBy = notification.getClaimedBy();
        Instant expectedLeaseExpiresAt = notification.getLeaseExpiresAt();

        Subscription subscription = subscriptionPort
                .findById(notification.getSubscriptionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription " + notification.getSubscriptionId() + " not found"));

        WebhookDeliveryRequest request = new WebhookDeliveryRequest(
                subscription.webhookUrl(),
                notification.getPayload(),
                notification.getId().toString(),
                subscription.hmacSecret());

        Instant now = clock.instant();
        DeliveryOutcome outcome = webhookSenderPort.send(request);
        deliveryMetricsPort.recordDeliveryAttempt(outcome.outcomeType(), outcome.durationMillis());

        int attemptNumber = notification.getAttemptCount() + 1;

        if (outcome.outcomeType() == DeliveryOutcomeType.SUCCESS) {
            notification.recordSuccess(now);
        } else {
            Classification classification = deliveryErrorClassifier.classify(outcome);
            if (classification == Classification.PERMANENT) {
                notification.recordDefinitiveFailure(now, FailureReason.PERMANENT_ERROR);
                deliveryMetricsPort.recordDefinitiveFailure(FailureReason.PERMANENT_ERROR);
            } else if (attemptNumber >= maxAttempts) {
                notification.recordDefinitiveFailure(now, FailureReason.MAX_ATTEMPTS_EXCEEDED);
                deliveryMetricsPort.recordDefinitiveFailure(FailureReason.MAX_ATTEMPTS_EXCEEDED);
            } else {
                Instant nextAttemptAt = retryPolicy.computeNextAttemptAt(
                        notification.getAttemptCount(), now, outcome.retryAfter(), randomGenerator);
                notification.recordRetryableFailure(now, nextAttemptAt);
                deliveryMetricsPort.recordRetryScheduled();
            }
        }

        DeliveryAttempt attempt = new DeliveryAttempt(
                UUID.randomUUID(),
                attemptNumber,
                now,
                Duration.ofMillis(outcome.durationMillis()),
                outcome.outcomeType(),
                outcome.httpStatusCode(),
                outcome.errorDetail(),
                outcome.responseSnippet(),
                subscription.webhookUrl(),
                Trigger.AUTOMATIC);

        notificationRepository.recordDeliveryResult(notification, attempt, expectedClaimedBy, expectedLeaseExpiresAt);
    }
}
