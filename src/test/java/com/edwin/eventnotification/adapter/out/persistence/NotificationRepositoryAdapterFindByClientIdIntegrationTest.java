package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NotificationRepositoryAdapterFindByClientIdIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private String client1;
    private String client2;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        client1 = "CLIENT001-" + suffix;
        client2 = "CLIENT002-" + suffix;
    }

    private Notification pending(String clientId, Instant eventOccurredAt) {
        return Notification.create(
                UUID.randomUUID(), "evt-" + UUID.randomUUID(), UUID.randomUUID(), clientId, eventOccurredAt, "payload");
    }

    private Notification completed(String clientId, Instant eventOccurredAt) {
        Notification notification = pending(clientId, eventOccurredAt);
        notification.claim("test-worker", Instant.now().plusSeconds(60));
        notification.recordSuccess(Instant.now());
        return notification;
    }

    @Test
    void doesNotReturnNotificationsFromAnotherClient() {
        Instant now = Instant.now();
        Notification own = pending(client1, now);
        Notification other = pending(client2, now);
        notificationRepository.saveIdempotent(own);
        notificationRepository.saveIdempotent(other);

        List<Notification> result =
                notificationRepository.findByClientId(client1, new NotificationQueryFilter(null, null, null));

        assertThat(result).extracting(Notification::getId).containsExactly(own.getId());
    }

    @Test
    void returnsAllNotificationsForClientWhenNoFiltersApplied() {
        Instant now = Instant.now();
        Notification first = pending(client1, now.minusSeconds(60));
        Notification second = pending(client1, now);
        notificationRepository.saveIdempotent(first);
        notificationRepository.saveIdempotent(second);

        List<Notification> result =
                notificationRepository.findByClientId(client1, new NotificationQueryFilter(null, null, null));

        assertThat(result).extracting(Notification::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void filtersByStatus() {
        Instant now = Instant.now();
        Notification pendingOne = pending(client1, now);
        Notification completedOne = completed(client1, now.minusSeconds(30));
        notificationRepository.saveIdempotent(pendingOne);
        notificationRepository.saveIdempotent(completedOne);

        List<Notification> result = notificationRepository.findByClientId(
                client1, new NotificationQueryFilter(null, null, NotificationStatus.COMPLETED));

        assertThat(result).extracting(Notification::getId).containsExactly(completedOne.getId());
    }

    @Test
    void filtersByEventOccurredAtRange() {
        Instant base = Instant.now();
        Notification early = pending(client1, base.minusSeconds(3600));
        Notification middle = pending(client1, base.minusSeconds(1800));
        Notification late = pending(client1, base);
        notificationRepository.saveIdempotent(early);
        notificationRepository.saveIdempotent(middle);
        notificationRepository.saveIdempotent(late);

        List<Notification> result = notificationRepository.findByClientId(
                client1, new NotificationQueryFilter(base.minusSeconds(2000), base.minusSeconds(900), null));

        assertThat(result).extracting(Notification::getId).containsExactly(middle.getId());
    }

    @Test
    void ordersResultsByEventOccurredAtDescending() {
        Instant base = Instant.now();
        Notification oldest = pending(client1, base.minusSeconds(120));
        Notification middle = pending(client1, base.minusSeconds(60));
        Notification newest = pending(client1, base);
        notificationRepository.saveIdempotent(oldest);
        notificationRepository.saveIdempotent(middle);
        notificationRepository.saveIdempotent(newest);

        List<Notification> result =
                notificationRepository.findByClientId(client1, new NotificationQueryFilter(null, null, null));

        assertThat(result)
                .extracting(Notification::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    void combinesStatusAndDateRangeFilters() {
        Instant base = Instant.now();
        Notification matching = completed(client1, base.minusSeconds(1800));
        Notification wrongStatus = pending(client1, base.minusSeconds(1800));
        Notification outsideRange = completed(client1, base.minusSeconds(7200));
        notificationRepository.saveIdempotent(matching);
        notificationRepository.saveIdempotent(wrongStatus);
        notificationRepository.saveIdempotent(outsideRange);

        List<Notification> result = notificationRepository.findByClientId(
                client1,
                new NotificationQueryFilter(
                        base.minusSeconds(3600), base.minusSeconds(900), NotificationStatus.COMPLETED));

        assertThat(result).extracting(Notification::getId).containsExactly(matching.getId());
    }
}
