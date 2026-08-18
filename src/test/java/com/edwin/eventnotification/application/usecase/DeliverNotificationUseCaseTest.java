package com.edwin.eventnotification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.port.out.DeliveryMetricsPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.application.port.out.WebhookDeliveryRequest;
import com.edwin.eventnotification.application.port.out.WebhookSenderPort;
import com.edwin.eventnotification.domain.delivery.DeliveryAttempt;
import com.edwin.eventnotification.domain.delivery.DeliveryErrorClassifier;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.delivery.RetryPolicy;
import com.edwin.eventnotification.domain.delivery.Trigger;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;
import com.edwin.eventnotification.domain.subscription.Subscription;

@ExtendWith(MockitoExtension.class)
class DeliverNotificationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private SubscriptionPort subscriptionPort;

    @Mock
    private WebhookSenderPort webhookSenderPort;

    @Mock
    private DeliveryMetricsPort deliveryMetricsPort;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RandomGenerator randomGenerator = RandomGenerator.getDefault();
    private final DeliveryErrorClassifier deliveryErrorClassifier = new DeliveryErrorClassifier();
    private final RetryPolicy retryPolicy = new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(10));

    private DeliverNotificationUseCase useCase(int maxAttempts) {
        return new DeliverNotificationUseCase(
                notificationRepository,
                subscriptionPort,
                webhookSenderPort,
                deliveryErrorClassifier,
                retryPolicy,
                clock,
                randomGenerator,
                deliveryMetricsPort,
                maxAttempts);
    }

    private Notification processingNotification(UUID id, UUID subscriptionId, int attemptCount) {
        return Notification.restore(
                id,
                "evt-1",
                subscriptionId,
                "client-1",
                NOW.minusSeconds(3600),
                "payload",
                NotificationStatus.PROCESSING,
                attemptCount,
                null,
                null,
                null,
                "worker-1",
                NOW.plusSeconds(60));
    }

    private Subscription subscription(UUID id) {
        return new Subscription(id, "client-1", "https://example.com/webhook", "secret", true);
    }

    private DeliveryOutcome outcome(DeliveryOutcomeType type, Integer httpStatusCode, Duration retryAfter) {
        return new DeliveryOutcome(type, httpStatusCode, 120L, null, null, retryAfter);
    }

    @Test
    void successOutcomeCompletesNotification() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.SUCCESS, 200, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.COMPLETED);

        ArgumentCaptor<DeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(notificationRepository, times(1))
                .recordDeliveryResult(
                        eq(notification), attemptCaptor.capture(), eq("worker-1"), eq(NOW.plusSeconds(60)));
        DeliveryAttempt attempt = attemptCaptor.getValue();
        assertThat(attempt.outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
        assertThat(attempt.attemptNumber()).isEqualTo(1);
        assertThat(attempt.trigger()).isEqualTo(Trigger.AUTOMATIC);
        assertThat(attempt.urlUsed()).isEqualTo(subscription.webhookUrl());
        assertThat(attempt.occurredAt()).isEqualTo(NOW);

        verify(deliveryMetricsPort).recordDeliveryAttempt(DeliveryOutcomeType.SUCCESS, 120L);
        verify(deliveryMetricsPort, never()).recordRetryScheduled();
        verify(deliveryMetricsPort, never()).recordDefinitiveFailure(any());
    }

    @Test
    void httpErrorClassifiedPermanentRecordsDefinitiveFailure() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 404, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.PERMANENT_ERROR);

        verify(deliveryMetricsPort).recordDeliveryAttempt(DeliveryOutcomeType.HTTP_ERROR, 120L);
        verify(deliveryMetricsPort).recordDefinitiveFailure(FailureReason.PERMANENT_ERROR);
        verify(deliveryMetricsPort, never()).recordRetryScheduled();
    }

    @Test
    void httpServerErrorIsRetryable() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 500, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt()).isNotNull().isAfter(NOW);

        verify(deliveryMetricsPort).recordDeliveryAttempt(DeliveryOutcomeType.HTTP_ERROR, 120L);
        verify(deliveryMetricsPort).recordRetryScheduled();
        verify(deliveryMetricsPort, never()).recordDefinitiveFailure(any());
    }

    @Test
    void httpTooManyRequestsIsRetryable() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 429, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void timeoutIsRetryable() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.TIMEOUT, null, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void connectionErrorIsRetryable() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.CONNECTION_ERROR, null, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void retryableFailureBecomesDefinitiveWhenMaxAttemptsReached() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 503, null));

        useCase(1).deliver(notificationId);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.MAX_ATTEMPTS_EXCEEDED);

        verify(deliveryMetricsPort).recordDefinitiveFailure(FailureReason.MAX_ATTEMPTS_EXCEEDED);
        verify(deliveryMetricsPort, never()).recordRetryScheduled();
    }

    @Test
    void retryAfterIsPropagatedExactlyToNextAttemptAt() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);
        Duration retryAfter = Duration.ofSeconds(30);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 503, retryAfter));

        useCase(8).deliver(notificationId);

        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plus(retryAfter));
    }

    @Test
    void nextAttemptAtUsesRetryPolicyJitterBoundsWhenNoRetryAfter() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.HTTP_ERROR, 500, null));

        useCase(8).deliver(notificationId);

        assertThat(notification.getNextAttemptAt()).isAfterOrEqualTo(NOW).isBeforeOrEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void attemptNumberReflectsPreviousAttemptCount() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 2);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.SUCCESS, 200, null));

        useCase(8).deliver(notificationId);

        ArgumentCaptor<DeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(notificationRepository)
                .recordDeliveryResult(
                        eq(notification), attemptCaptor.capture(), eq("worker-1"), eq(NOW.plusSeconds(60)));
        assertThat(attemptCaptor.getValue().attemptNumber()).isEqualTo(3);
    }

    @Test
    void throwsWhenNotificationDoesNotExist() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(8).deliver(notificationId)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(webhookSenderPort);
        verify(notificationRepository, never()).recordDeliveryResult(any(), any(), any(), any());
    }

    @Test
    void throwsWhenSubscriptionDoesNotExist() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(8).deliver(notificationId)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(webhookSenderPort);
        verify(notificationRepository, never()).recordDeliveryResult(any(), any(), any(), any());
    }

    @Test
    void webhookSenderReceivesDataFromNotificationAndSubscription() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.SUCCESS, 200, null));

        useCase(8).deliver(notificationId);

        ArgumentCaptor<WebhookDeliveryRequest> requestCaptor = ArgumentCaptor.forClass(WebhookDeliveryRequest.class);
        verify(webhookSenderPort).send(requestCaptor.capture());
        WebhookDeliveryRequest request = requestCaptor.getValue();
        assertThat(request.url()).isEqualTo(subscription.webhookUrl());
        assertThat(request.payload()).isEqualTo(notification.getPayload());
        assertThat(request.idempotencyKey()).isEqualTo(notification.getId().toString());
        assertThat(request.hmacSecret()).isEqualTo(subscription.hmacSecret());
    }

    @Test
    void recordDeliveryResultCalledExactlyOnceWithUpdatedNotificationAndAttempt() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.SUCCESS, 200, null));

        useCase(8).deliver(notificationId);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        ArgumentCaptor<DeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(notificationRepository, times(1))
                .recordDeliveryResult(
                        notificationCaptor.capture(), attemptCaptor.capture(), eq("worker-1"), eq(NOW.plusSeconds(60)));

        assertThat(notificationCaptor.getValue()).isSameAs(notification);
        assertThat(notificationCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.COMPLETED);
        assertThat(attemptCaptor.getValue().outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
    }

    @Test
    void claimTokenIsCapturedBeforeTheNotificationIsMutatedByTheOutcomeTransition() {
        UUID notificationId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Notification notification = processingNotification(notificationId, subscriptionId, 0);
        Subscription subscription = subscription(subscriptionId);

        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(subscriptionPort.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(webhookSenderPort.send(any())).thenReturn(outcome(DeliveryOutcomeType.SUCCESS, 200, null));

        useCase(8).deliver(notificationId);

        // recordSuccess() nulls out claimedBy/leaseExpiresAt on the notification itself - proving
        // the use case captured the claim token beforehand, not by reading it off this object
        // after the transition already cleared it.
        assertThat(notification.getClaimedBy()).isNull();
        assertThat(notification.getLeaseExpiresAt()).isNull();

        ArgumentCaptor<String> claimedByCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> leaseExpiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(notificationRepository)
                .recordDeliveryResult(any(), any(), claimedByCaptor.capture(), leaseExpiresAtCaptor.capture());

        assertThat(claimedByCaptor.getValue()).isEqualTo("worker-1");
        assertThat(leaseExpiresAtCaptor.getValue()).isEqualTo(NOW.plusSeconds(60));
    }
}
