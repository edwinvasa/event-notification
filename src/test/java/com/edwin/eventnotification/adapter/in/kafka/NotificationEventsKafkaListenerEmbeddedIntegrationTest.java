package com.edwin.eventnotification.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.domain.event.Event;

/**
 * End-to-end verification against a real (embedded, in-JVM) Kafka broker via spring-kafka-test -
 * no Docker/Testcontainers involved, consistent with this project never using Testcontainers.
 * {@link IngestEventPort} is replaced with a Mockito bean so this test stays focused on "does a
 * real Kafka message reach the port with the right Event" - the Event -> Postgres path is already
 * thoroughly covered by NotificationEventsJsonIngestionRunnerIntegrationTest via the same port.
 */
@SpringBootTest(
        properties = {
            "ingestion.kafka.enabled=true",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "ingestion.json.enabled=false",
            "demo.seed.enabled=false"
        })
@EmbeddedKafka(partitions = 1, topics = "notification-events")
class NotificationEventsKafkaListenerEmbeddedIntegrationTest {

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoBean
    private IngestEventPort ingestEventPort;

    @BeforeEach
    void waitUntilTheListenerContainerHasJoinedTheConsumerGroup() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @Test
    void aRealKafkaMessageReachesIngestEventPortWithTheCorrectEvent() throws Exception {
        String message =
                """
                {"event_id": "EVT001", "event_type": "credit_card_payment", "client_id": "CLIENT001",
                 "content": "Payment received", "delivery_date": "2026-08-18T10:00:00Z"}
                """;

        kafkaTemplate.send("notification-events", message).get(10, TimeUnit.SECONDS);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ingestEventPort, timeout(10000)).ingest(captor.capture());

        Event event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("EVT001");
        assertThat(event.eventType()).isEqualTo("credit_card_payment");
        assertThat(event.clientId()).isEqualTo("CLIENT001");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-18T10:00:00Z"));
    }

    @Test
    void malformedMessageOnTheRealTopicNeverReachesIngestEventPort() throws Exception {
        kafkaTemplate.send("notification-events", "not valid json").get(10, TimeUnit.SECONDS);

        // Waits the full delay, then asserts it was never called - not a race like a bare verify.
        verify(ingestEventPort, after(2000).never()).ingest(any());
    }
}
