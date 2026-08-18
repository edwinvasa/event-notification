package com.edwin.eventnotification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.exception.NotificationNotFoundException;
import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.result.DetailResult;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@ExtendWith(MockitoExtension.class)
class NotificationQueryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CLIENT_ID = "client-1";

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationQueryUseCase useCase() {
        return new NotificationQueryUseCase(notificationRepository);
    }

    private Notification notification(UUID id, String clientId, NotificationStatus status) {
        return Notification.restore(
                id,
                "evt-1",
                UUID.randomUUID(),
                clientId,
                NOW.minusSeconds(3600),
                "payload",
                status,
                2,
                null,
                NOW.minusSeconds(60),
                status == NotificationStatus.FAILED ? FailureReason.PERMANENT_ERROR : null,
                null,
                null);
    }

    @Test
    void listDelegatesToTheRepositoryWithTheGivenClientAndFilter() {
        NotificationQueryFilter filter = new NotificationQueryFilter(null, null, NotificationStatus.FAILED);
        List<Notification> expected =
                List.of(notification(UUID.randomUUID(), CLIENT_ID, NotificationStatus.FAILED));
        when(notificationRepository.findByClientId(CLIENT_ID, filter)).thenReturn(expected);

        List<Notification> result = useCase().list(CLIENT_ID, filter);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getDetailReturnsTheFullStateIncludingPayloadWhenOwnedByTheClient() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = notification(notificationId, CLIENT_ID, NotificationStatus.COMPLETED);
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID))
                .thenReturn(Optional.of(notification));

        DetailResult result = useCase().getDetail(notificationId, CLIENT_ID);

        assertThat(result.notificationId()).isEqualTo(notificationId);
        assertThat(result.eventId()).isEqualTo("evt-1");
        assertThat(result.status()).isEqualTo(NotificationStatus.COMPLETED);
        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(result.payload()).isEqualTo("payload");
    }

    @Test
    void getDetailThrowsNotFoundWhenNotificationDoesNotExist() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().getDetail(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void getDetailThrowsNotFoundWhenNotificationBelongsToAnotherClient() {
        UUID notificationId = UUID.randomUUID();
        // The scoped query itself finds nothing for this (id, clientId) pair - the row exists for
        // a different client, which is indistinguishable at this level from not existing at all.
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().getDetail(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotFoundException.class);
    }
}
