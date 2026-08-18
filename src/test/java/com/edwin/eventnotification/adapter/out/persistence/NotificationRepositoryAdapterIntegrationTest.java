package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NotificationRepositoryAdapterIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void saveIdempotentDoesNotCreateDuplicateForSameEventAndSubscription() {
        String eventId = "evt-" + UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        Notification notification = Notification.create(
                UUID.randomUUID(),
                eventId,
                subscriptionId,
                "client-1",
                Instant.now(),
                "payload");

        notificationRepository.saveIdempotent(notification);

        assertThatCode(() -> notificationRepository.saveIdempotent(notification)).doesNotThrowAnyException();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE event_id = ? AND subscription_id = ?",
                Integer.class,
                eventId,
                subscriptionId);

        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void findByIdAndClientIdReturnsTheNotificationWhenItBelongsToThatClient() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notificationRepository.saveIdempotent(notification);

        assertThat(notificationRepository.findByIdAndClientId(notificationId, "client-1"))
                .isPresent()
                .get()
                .extracting(Notification::getId)
                .isEqualTo(notificationId);
    }

    @Test
    void findByIdAndClientIdIsEmptyWhenTheNotificationBelongsToAnotherClient() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = Notification.create(
                notificationId, "evt-" + UUID.randomUUID(), UUID.randomUUID(), "client-1", Instant.now(), "payload");
        notificationRepository.saveIdempotent(notification);

        assertThat(notificationRepository.findByIdAndClientId(notificationId, "client-2")).isEmpty();
    }

    @Test
    void findByIdAndClientIdIsEmptyWhenTheNotificationDoesNotExist() {
        assertThat(notificationRepository.findByIdAndClientId(UUID.randomUUID(), "client-1")).isEmpty();
    }
}
