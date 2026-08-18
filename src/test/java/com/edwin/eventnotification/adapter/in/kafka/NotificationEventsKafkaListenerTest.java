package com.edwin.eventnotification.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.domain.event.Event;

/**
 * Verifies the listener in complete isolation from Kafka/Spring: {@link IngestEventPort} is
 * mocked and {@code onMessage} is called directly with raw strings.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventsKafkaListenerTest {

    @Mock
    private IngestEventPort ingestEventPort;

    private NotificationEventsKafkaListener listener() {
        return new NotificationEventsKafkaListener(ingestEventPort);
    }

    @Test
    void validMessageBuildsTheCorrectEventAndCallsIngestEventPort() {
        String message =
                """
                {"event_id": "EVT001", "event_type": "credit_card_payment", "client_id": "CLIENT001",
                 "content": "Payment received", "delivery_date": "2026-08-18T10:00:00Z"}
                """;

        listener().onMessage(message);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ingestEventPort).ingest(captor.capture());

        Event event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("EVT001");
        assertThat(event.eventType()).isEqualTo("credit_card_payment");
        assertThat(event.clientId()).isEqualTo("CLIENT001");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-18T10:00:00Z"));
        assertThat(event.payload())
                .contains("EVT001")
                .contains("credit_card_payment")
                .contains("CLIENT001")
                .contains("Payment received")
                .doesNotContain("delivery_date")
                .doesNotContain("delivery_status");
    }

    @Test
    void malformedJsonIsSkippedWithoutCallingIngestEventPort() {
        listener().onMessage("not valid json");

        verifyNoInteractions(ingestEventPort);
    }

    @Test
    void invalidEventIsSkippedWithoutCallingIngestEventPort() {
        String message =
                """
                {"event_id": "EVT001", "event_type": "credit_card_payment", "client_id": "CLIENT001",
                 "content": "Payment received", "delivery_date": "not-a-date"}
                """;

        listener().onMessage(message);

        verifyNoInteractions(ingestEventPort);
    }

    @Test
    void exceptionsFromIngestEventPortAreNotCaughtByTheListener() {
        String message =
                """
                {"event_id": "EVT001", "event_type": "credit_card_payment", "client_id": "CLIENT001",
                 "content": "Payment received", "delivery_date": "2026-08-18T10:00:00Z"}
                """;
        doThrow(new IllegalStateException("boom")).when(ingestEventPort).ingest(any());

        assertThatThrownBy(() -> listener().onMessage(message)).isInstanceOf(IllegalStateException.class);
    }
}
