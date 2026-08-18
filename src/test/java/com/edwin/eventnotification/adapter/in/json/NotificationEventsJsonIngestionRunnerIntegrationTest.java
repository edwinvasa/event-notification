package com.edwin.eventnotification.adapter.in.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.edwin.eventnotification.adapter.out.persistence.NotificationJpaRepository;
import com.edwin.eventnotification.adapter.out.persistence.SubscriptionJpaEntity;
import com.edwin.eventnotification.adapter.out.persistence.SubscriptionJpaRepository;
import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

/**
 * Demonstrates the full flow JSON -> Event -> IngestEventUseCase -> Notification -> PostgreSQL,
 * using a small fixture built per test (never the challenge's real file) against real Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationEventsJsonIngestionRunnerIntegrationTest {

    @Autowired
    private IngestEventPort ingestEventPort;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    private final List<UUID> subscriptionIds = new ArrayList<>();
    private final List<UUID> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        notificationIds.forEach(notificationJpaRepository::deleteById);
        notificationIds.clear();
        subscriptionIds.forEach(subscriptionJpaRepository::deleteById);
        subscriptionIds.clear();
    }

    private NotificationEventsJsonIngestionRunner runner() {
        return new NotificationEventsJsonIngestionRunner(ingestEventPort, resourceLoader, "unused", true);
    }

    private void seedActiveSubscription(String clientId) {
        UUID id = UUID.randomUUID();
        subscriptionIds.add(id);
        subscriptionJpaRepository.save(
                new SubscriptionJpaEntity(id, clientId, "https://example.com/webhook", "secret", true));
    }

    private Resource fixture(String... events) {
        String json = "{\"events\":[" + String.join(",", events) + "]}";
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    private String event(String eventId, String content, String deliveryDate, String clientId) {
        return """
                {"event_id": "%s", "event_type": "test_event", "content": "%s",
                 "delivery_date": "%s", "delivery_status": "completed", "client_id": "%s"}
                """
                .formatted(eventId, content, deliveryDate, clientId);
    }

    private List<Notification> notificationsFor(String clientId) {
        List<Notification> notifications =
                notificationRepository.findByClientId(clientId, new NotificationQueryFilter(null, null, null));
        notificationIds.addAll(notifications.stream().map(Notification::getId).toList());
        return notifications;
    }

    @Test
    void ingestsEventsIntoRealPostgresForAClientWithAnActiveSubscription() {
        String clientId = "client-" + UUID.randomUUID();
        seedActiveSubscription(clientId);
        String eventId1 = "evt-" + UUID.randomUUID();
        String eventId2 = "evt-" + UUID.randomUUID();
        Resource resource = fixture(
                event(eventId1, "First event", "2026-01-01T00:00:00Z", clientId),
                event(eventId2, "Second event", "2026-01-02T00:00:00Z", clientId));

        runner().ingestFrom(resource);

        List<Notification> notifications = notificationsFor(clientId);
        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(Notification::getEventId).containsExactlyInAnyOrder(eventId1, eventId2);
        assertThat(notifications).allMatch(n -> n.getStatus() == NotificationStatus.PENDING);

        Notification first =
                notifications.stream().filter(n -> n.getEventId().equals(eventId1)).findFirst().orElseThrow();
        assertThat(first.getPayload()).contains(eventId1).contains("test_event").contains(clientId);
    }

    @Test
    void clientWithoutAnActiveSubscriptionGetsNoNotifications() {
        String clientId = "client-" + UUID.randomUUID();
        String eventId = "evt-" + UUID.randomUUID();
        Resource resource = fixture(event(eventId, "Orphan event", "2026-01-01T00:00:00Z", clientId));

        runner().ingestFrom(resource);

        assertThat(notificationsFor(clientId)).isEmpty();
    }

    @Test
    void reprocessingTheSameFixtureTwiceDoesNotCreateDuplicates() {
        String clientId = "client-" + UUID.randomUUID();
        seedActiveSubscription(clientId);
        String eventId = "evt-" + UUID.randomUUID();
        Resource resource = fixture(event(eventId, "Repeated event", "2026-01-01T00:00:00Z", clientId));

        runner().ingestFrom(resource);
        runner().ingestFrom(resource);

        assertThat(notificationsFor(clientId)).hasSize(1);
    }
}
