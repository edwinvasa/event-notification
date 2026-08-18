package com.edwin.eventnotification.adapter.in.kafka;

import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.domain.event.Event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Input adapter that consumes the notification events Kafka topic and feeds each message into
 * {@link IngestEventPort} (ADR-001, ADR-003) - the application core has no knowledge of Kafka.
 *
 * <p>Responsibility is deliberately limited to: receive the raw message, parse/map it, build an
 * {@link Event}, call the port. No JPA, repositories, PostgreSQL, NotificationFactory or
 * SubscriptionPort here - exactly the same boundary already established by the JSON adapter.
 *
 * <p>Disabled by default ({@code ingestion.kafka.enabled=false}) via {@link ConditionalOnProperty}
 * on the whole bean, so the listener container is never created and no broker connection is even
 * attempted - the application and test suite start normally without a Kafka broker available.
 *
 * <p>Error handling (deliberately simple, per this iteration's scope): malformed JSON or an
 * invalid event are logged and skipped without breaking the consumer, exactly like the JSON
 * adapter. If {@link IngestEventPort#ingest} itself throws, the exception is left to propagate so
 * Spring Kafka's own default error handling applies (offset not committed for that record) -
 * consistent with ADR-004 ("el commit del offset se realizará después de que la persistencia haya
 * sido completada correctamente"). No custom retry topics or dead-letter topic are configured yet.
 */
@Component
@ConditionalOnProperty(name = "ingestion.kafka.enabled", havingValue = "true")
public class NotificationEventsKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventsKafkaListener.class);

    private final IngestEventPort ingestEventPort;
    private final ObjectMapper objectMapper;

    public NotificationEventsKafkaListener(IngestEventPort ingestEventPort) {
        this.ingestEventPort = Objects.requireNonNull(ingestEventPort, "ingestEventPort must not be null");
        this.objectMapper =
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    }

    @KafkaListener(topics = "${kafka.topic}", groupId = "${kafka.group-id}")
    public void onMessage(String rawMessage) {
        NotificationEventKafkaMessage message;
        try {
            message = objectMapper.readValue(rawMessage, NotificationEventKafkaMessage.class);
        } catch (JacksonException e) {
            log.error("Skipping invalid Kafka notification event message (malformed JSON): {}", e.getMessage());
            return;
        }

        Event event;
        try {
            event = toEvent(message);
        } catch (Exception e) {
            log.error("Skipping invalid notification event from Kafka {}: {}", message, e.getMessage());
            return;
        }

        ingestEventPort.ingest(event);
    }

    private Event toEvent(NotificationEventKafkaMessage message) {
        Instant occurredAt = Instant.parse(message.deliveryDate());
        String payload = objectMapper.writeValueAsString(new NotificationEventPayload(
                message.eventId(), message.eventType(), message.clientId(), message.content()));
        return new Event(message.eventId(), message.eventType(), message.clientId(), payload, occurredAt);
    }
}
