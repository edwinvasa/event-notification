package com.edwin.eventnotification.adapter.in.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.port.in.DeliverNotificationPort;
import com.edwin.eventnotification.application.port.out.NotificationClaimRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkerTest {

    @Mock
    private NotificationClaimRepository notificationClaimRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private DeliverNotificationPort deliverNotificationPort;

    private final ConcurrencyLimiter concurrencyLimiter = new ConcurrencyLimiter(20, 10);

    private DeliveryWorker newWorker() {
        return newWorker(concurrencyLimiter);
    }

    private DeliveryWorker newWorker(ConcurrencyLimiter limiter) {
        return new DeliveryWorker(
                notificationClaimRepository,
                notificationRepository,
                deliverNotificationPort,
                limiter,
                20,
                Duration.ofMinutes(2));
    }

    private Notification notificationFor(UUID id, String clientId) {
        return Notification.create(id, "evt-" + id, UUID.randomUUID(), clientId, Instant.now(), "{}");
    }

    @Test
    void processOneDeliversTheClaimedNotification() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId))
                .thenReturn(Optional.of(notificationFor(notificationId, "client-1")));

        newWorker().processOne(notificationId);

        verify(deliverNotificationPort).deliver(notificationId);
    }

    @Test
    void processOneSkipsDeliveryWhenTheNotificationCannotBeFound() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        newWorker().processOne(notificationId);

        verifyNoInteractions(deliverNotificationPort);
    }

    @Test
    void processOneDoesNotPropagateAnExceptionThrownByDelivery() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId))
                .thenReturn(Optional.of(notificationFor(notificationId, "client-1")));
        doThrow(new IllegalStateException("boom")).when(deliverNotificationPort).deliver(notificationId);

        DeliveryWorker worker = newWorker();

        assertThatCode(() -> worker.processOne(notificationId)).doesNotThrowAnyException();
    }

    @Test
    void pollAndDispatchClaimsAndDeliversEachNotificationConcurrently() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(notificationClaimRepository.claimDueBatch(eq(20), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(List.of(first, second));
        when(notificationRepository.findById(first)).thenReturn(Optional.of(notificationFor(first, "client-1")));
        when(notificationRepository.findById(second)).thenReturn(Optional.of(notificationFor(second, "client-2")));

        newWorker().pollAndDispatch();

        verify(deliverNotificationPort, timeout(2000)).deliver(first);
        verify(deliverNotificationPort, timeout(2000)).deliver(second);
    }

    @Test
    void pollAndDispatchContinuesWithTheRestOfTheBatchWhenOneDeliveryFails() {
        UUID failing = UUID.randomUUID();
        UUID succeeding = UUID.randomUUID();
        when(notificationClaimRepository.claimDueBatch(eq(20), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(List.of(failing, succeeding));
        when(notificationRepository.findById(failing)).thenReturn(Optional.of(notificationFor(failing, "client-1")));
        when(notificationRepository.findById(succeeding))
                .thenReturn(Optional.of(notificationFor(succeeding, "client-2")));
        doThrow(new IllegalStateException("boom")).when(deliverNotificationPort).deliver(failing);

        newWorker().pollAndDispatch();

        verify(deliverNotificationPort, timeout(2000)).deliver(failing);
        verify(deliverNotificationPort, timeout(2000)).deliver(succeeding);
    }

    @Test
    void pollAndDispatchDoesNotClaimWhenNoGlobalCapacityIsAvailable() throws InterruptedException {
        ConcurrencyLimiter saturated = new ConcurrencyLimiter(1, 10);
        saturated.acquire("some-other-client");

        newWorker(saturated).pollAndDispatch();

        verifyNoInteractions(notificationClaimRepository);
    }

    @Test
    void pollAndDispatchCapsTheClaimLimitToTheAvailableGlobalCapacity() throws InterruptedException {
        ConcurrencyLimiter partiallySaturated = new ConcurrencyLimiter(20, 10);
        for (int i = 0; i < 17; i++) {
            partiallySaturated.acquire("client-" + i);
        }
        when(notificationClaimRepository.claimDueBatch(eq(3), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(List.of());

        newWorker(partiallySaturated).pollAndDispatch();

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(notificationClaimRepository, never()).claimDueBatch(eq(20), anyString(), eq(Duration.ofMinutes(2)));
        verify(notificationClaimRepository).claimDueBatch(limitCaptor.capture(), anyString(), eq(Duration.ofMinutes(2)));
        assertThat(limitCaptor.getValue()).isEqualTo(3);
    }
}
