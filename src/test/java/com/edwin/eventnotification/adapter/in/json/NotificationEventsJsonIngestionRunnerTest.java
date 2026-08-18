package com.edwin.eventnotification.adapter.in.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.domain.event.Event;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the adapter in complete isolation from Spring and PostgreSQL: {@link IngestEventPort}
 * is mocked, and every scenario is exercised against in-memory {@link Resource} instances.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventsJsonIngestionRunnerTest {

    @Mock
    private IngestEventPort ingestEventPort;

    @Mock
    private ResourceLoader resourceLoader;

    private NotificationEventsJsonIngestionRunner runner(boolean enabled) {
        return new NotificationEventsJsonIngestionRunner(ingestEventPort, resourceLoader, "file:irrelevant.json", enabled);
    }

    private Resource jsonResource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ingestsEveryValidEventWithAStructuredPayloadExcludingDeliveryFields() throws Exception {
        String json =
                """
                {
                  "events": [
                    {"event_id": "EVT001", "event_type": "credit_card_payment", "content": "Payment received",
                     "delivery_date": "2024-03-15T09:30:22Z", "delivery_status": "completed", "client_id": "CLIENT001"},
                    {"event_id": "EVT002", "event_type": "debit_card_withdrawal", "content": "ATM withdrawal",
                     "delivery_date": "2024-03-15T10:15:45Z", "delivery_status": "completed", "client_id": "CLIENT001"}
                  ]
                }
                """;

        runner(true).ingestFrom(jsonResource(json));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ingestEventPort, times(2)).ingest(captor.capture());

        Event first = captor.getAllValues().get(0);
        assertThat(first.eventId()).isEqualTo("EVT001");
        assertThat(first.eventType()).isEqualTo("credit_card_payment");
        assertThat(first.clientId()).isEqualTo("CLIENT001");
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2024-03-15T09:30:22Z"));

        ObjectMapper plainMapper = new ObjectMapper();
        JsonNode payload = plainMapper.readTree(first.payload());
        assertThat(payload.get("event_id").asString()).isEqualTo("EVT001");
        assertThat(payload.get("event_type").asString()).isEqualTo("credit_card_payment");
        assertThat(payload.get("client_id").asString()).isEqualTo("CLIENT001");
        assertThat(payload.get("content").asString()).isEqualTo("Payment received");
        assertThat(payload.has("delivery_date")).isFalse();
        assertThat(payload.has("delivery_status")).isFalse();
    }

    @Test
    void skipsAnInvalidEventButContinuesWithTheRest() {
        String json =
                """
                {
                  "events": [
                    {"event_id": "EVT001", "event_type": "credit_card_payment", "content": "Valid",
                     "delivery_date": "2024-03-15T09:30:22Z", "delivery_status": "completed", "client_id": "CLIENT001"},
                    {"event_id": "EVT002", "event_type": "credit_card_payment", "content": "Bad date",
                     "delivery_date": "not-a-date", "delivery_status": "completed", "client_id": "CLIENT001"},
                    {"event_id": "EVT003", "event_type": "credit_card_payment", "content": "Valid too",
                     "delivery_date": "2024-03-15T11:00:00Z", "delivery_status": "completed", "client_id": "CLIENT001"}
                  ]
                }
                """;

        runner(true).ingestFrom(jsonResource(json));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ingestEventPort, times(2)).ingest(captor.capture());
        assertThat(captor.getAllValues()).extracting(Event::eventId).containsExactly("EVT001", "EVT003");
    }

    @Test
    void doesNothingWhenTheResourceCannotBeRead() {
        Resource missing = new ClassPathResource("does-not-exist-" + System.nanoTime() + ".json");

        runner(true).ingestFrom(missing);

        verifyNoInteractions(ingestEventPort);
    }

    @Test
    void doesNothingWhenTheJsonIsMalformed() {
        runner(true).ingestFrom(jsonResource("not valid json"));

        verifyNoInteractions(ingestEventPort);
    }

    @Test
    void doesNothingWhenTheEventsArrayIsMissing() {
        runner(true).ingestFrom(jsonResource("{}"));

        verifyNoInteractions(ingestEventPort);
    }

    @Test
    void runDoesNothingWhenDisabled() {
        runner(false).run();

        verifyNoInteractions(ingestEventPort, resourceLoader);
    }
}
