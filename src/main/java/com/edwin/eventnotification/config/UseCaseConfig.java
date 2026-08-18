package com.edwin.eventnotification.config;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.edwin.eventnotification.application.port.in.DeliverNotificationPort;
import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.application.port.in.NotificationQueryPort;
import com.edwin.eventnotification.application.port.in.ReplayNotificationPort;
import com.edwin.eventnotification.application.port.out.DeliveryMetricsPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.application.port.out.WebhookSenderPort;
import com.edwin.eventnotification.application.usecase.DeliverNotificationUseCase;
import com.edwin.eventnotification.application.usecase.IngestEventUseCase;
import com.edwin.eventnotification.application.usecase.NotificationQueryUseCase;
import com.edwin.eventnotification.application.usecase.ReplayNotificationUseCase;
import com.edwin.eventnotification.domain.delivery.DeliveryErrorClassifier;
import com.edwin.eventnotification.domain.delivery.RetryPolicy;
import com.edwin.eventnotification.domain.notification.NotificationFactory;

@Configuration
public class UseCaseConfig {

    @Bean
    public IngestEventPort ingestEventUseCase(
            SubscriptionPort subscriptionPort, NotificationRepository notificationRepository) {
        return new IngestEventUseCase(subscriptionPort, notificationRepository, new NotificationFactory());
    }

    @Bean
    public NotificationQueryPort notificationQueryUseCase(NotificationRepository notificationRepository) {
        return new NotificationQueryUseCase(notificationRepository);
    }

    @Bean
    public ReplayNotificationPort replayNotificationUseCase(NotificationRepository notificationRepository) {
        return new ReplayNotificationUseCase(notificationRepository);
    }

    @Bean
    @ConditionalOnBean({SubscriptionPort.class, WebhookSenderPort.class})
    public DeliverNotificationPort deliverNotificationUseCase(
            NotificationRepository notificationRepository,
            SubscriptionPort subscriptionPort,
            WebhookSenderPort webhookSenderPort,
            RetryPolicy retryPolicy,
            Clock clock,
            RandomGenerator randomGenerator,
            DeliveryMetricsPort deliveryMetricsPort,
            @Value("${delivery.retry.max-attempts}") int maxAttempts) {
        return new DeliverNotificationUseCase(
                notificationRepository,
                subscriptionPort,
                webhookSenderPort,
                new DeliveryErrorClassifier(),
                retryPolicy,
                clock,
                randomGenerator,
                deliveryMetricsPort,
                maxAttempts);
    }
}
