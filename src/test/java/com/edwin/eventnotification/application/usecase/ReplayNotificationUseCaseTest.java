package com.edwin.eventnotification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.exception.NotificationNotFoundException;
import com.edwin.eventnotification.application.exception.NotificationNotReplayableException;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.application.result.ReplayResult;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@ExtendWith(MockitoExtension.class)
class ReplayNotificationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CLIENT_ID = "client-1";

    @Mock
    private NotificationRepository notificationRepository;

    private ReplayNotificationUseCase useCase() {
        return new ReplayNotificationUseCase(notificationRepository);
    }

    private Notification notification(UUID id, String clientId, NotificationStatus status, int attemptCount) {
        return Notification.restore(
                id,
                "evt-1",
                UUID.randomUUID(),
                clientId,
                NOW.minusSeconds(3600),
                "payload",
                status,
                attemptCount,
                null,
                NOW.minusSeconds(60),
                status == NotificationStatus.FAILED ? FailureReason.PERMANENT_ERROR : null,
                null,
                null);
    }

    @Test
    void replaySucceedsWhenNotificationIsFailedAndBelongsToClient() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = notification(notificationId, CLIENT_ID, NotificationStatus.FAILED, 3);

        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.transitionFailedToPending(notificationId)).thenReturn(true);

        ReplayResult result = useCase().replay(notificationId, CLIENT_ID);

        assertThat(result.notificationId()).isEqualTo(notificationId);
        assertThat(result.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(result.attemptCount()).isEqualTo(3);
        assertThat(result.lastAttemptedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void replayThrowsNotFoundWhenNotificationDoesNotExist() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().replay(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotFoundException.class);

        verify(notificationRepository, never()).transitionFailedToPending(notificationId);
    }

    @Test
    void replayThrowsNotFoundWhenNotificationBelongsToAnotherClient() {
        UUID notificationId = UUID.randomUUID();
        // The scoped query itself finds nothing for this (id, clientId) pair - the row exists for
        // a different client, which is indistinguishable at this level from not existing at all.
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().replay(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotFoundException.class);

        verify(notificationRepository, never()).transitionFailedToPending(notificationId);
    }

    @Test
    void replayThrowsNotReplayableWhenStatusIsNotFailed() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = notification(notificationId, CLIENT_ID, NotificationStatus.PENDING, 1);
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID))
                .thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> useCase().replay(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotReplayableException.class);

        verify(notificationRepository, never()).transitionFailedToPending(notificationId);
    }

    @Test
    void replayThrowsNotReplayableWhenTheConditionalTransitionLosesAConcurrentRace() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = notification(notificationId, CLIENT_ID, NotificationStatus.FAILED, 3);
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.transitionFailedToPending(notificationId)).thenReturn(false);

        assertThatThrownBy(() -> useCase().replay(notificationId, CLIENT_ID))
                .isInstanceOf(NotificationNotReplayableException.class);
    }

    @Test
    void replayDoesNotResetAttemptCount() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = notification(notificationId, CLIENT_ID, NotificationStatus.FAILED, 7);
        when(notificationRepository.findByIdAndClientId(notificationId, CLIENT_ID))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.transitionFailedToPending(notificationId)).thenReturn(true);

        ReplayResult result = useCase().replay(notificationId, CLIENT_ID);

        assertThat(result.attemptCount()).isEqualTo(7);
    }
}
