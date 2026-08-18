package com.edwin.eventnotification.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.edwin.eventnotification.adapter.out.persistence.NotificationJpaRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationBacklogMetricsIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    private final List<UUID> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdIds.forEach(notificationJpaRepository::deleteById);
        createdIds.clear();
    }

    @Test
    void gaugeReflectsTheNumberOfDuePendingNotificationsInRealPostgres() {
        double before = meterRegistry.get("notification.backlog.pending").gauge().value();

        UUID id = UUID.randomUUID();
        createdIds.add(id);
        Notification notification = Notification.create(
                id, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notificationRepository.saveIdempotent(notification);

        double after = meterRegistry.get("notification.backlog.pending").gauge().value();

        assertThat(after).isEqualTo(before + 1);
    }
}
