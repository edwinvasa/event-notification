package com.edwin.eventnotification.adapter.out.metrics;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.edwin.eventnotification.adapter.out.persistence.NotificationJpaRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers {@code notification.backlog.pending}: how much PENDING work is due right now. Queries
 * NotificationJpaRepository directly (not the application-facing NotificationRepository port) -
 * this is an operational/metrics signal, not a business capability any use case needs, the same
 * reasoning already applied to DemoSubscriptionSeedRunner.
 */
@Component
public class NotificationBacklogMetrics {

    private final NotificationJpaRepository notificationJpaRepository;
    private final Clock clock;

    public NotificationBacklogMetrics(
            MeterRegistry meterRegistry, NotificationJpaRepository notificationJpaRepository, Clock clock) {
        this.notificationJpaRepository = notificationJpaRepository;
        this.clock = clock;
        Gauge.builder("notification.backlog.pending", this, NotificationBacklogMetrics::countDuePending)
                .description("Number of PENDING notifications due to be claimed right now")
                .register(meterRegistry);
    }

    private double countDuePending() {
        return notificationJpaRepository.countDuePending(clock.instant());
    }
}
