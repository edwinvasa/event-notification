package com.edwin.eventnotification.adapter.in.json;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.domain.event.Event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Input adapter that reads {@code notification_events.json} (the challenge's provided input) and
 * feeds each entry into {@link IngestEventPort}, exactly like a Kafka consumer would (ADR-001,
 * ADR-003) - the application core has no knowledge of this adapter's existence.
 *
 * <p>Disabled by default ({@code ingestion.json.enabled=false}) so it never runs as a side effect
 * of the application's own test suite; it is meant to be enabled explicitly for a local/demo run.
 */
@Component
@Order(2)
public class NotificationEventsJsonIngestionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventsJsonIngestionRunner.class);

    private final IngestEventPort ingestEventPort;
    private final ResourceLoader resourceLoader;
    private final String location;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public NotificationEventsJsonIngestionRunner(
            IngestEventPort ingestEventPort,
            ResourceLoader resourceLoader,
            @Value("${ingestion.json.location}") String location,
            @Value("${ingestion.json.enabled}") boolean enabled) {
        this.ingestEventPort = Objects.requireNonNull(ingestEventPort, "ingestEventPort must not be null");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.enabled = enabled;
        this.objectMapper =
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("JSON event ingestion is disabled (ingestion.json.enabled=false); skipping.");
            return;
        }
        ingestFrom(resourceLoader.getResource(location));
    }

    void ingestFrom(Resource resource) {
        NotificationEventsFile file;
        try (InputStream inputStream = resource.getInputStream()) {
            file = objectMapper.readValue(inputStream, NotificationEventsFile.class);
        } catch (IOException | JacksonException e) {
            log.error("Could not read notification events file at {}: {}", resource, e.getMessage());
            return;
        }

        if (file.events() == null) {
            log.warn("Notification events file at {} has no 'events' array; nothing to ingest.", resource);
            return;
        }

        for (NotificationEventJson eventJson : file.events()) {
            try {
                ingestEventPort.ingest(toEvent(eventJson));
            } catch (Exception e) {
                log.error("Skipping invalid notification event {}: {}", eventJson, e.getMessage());
            }
        }
    }

    private Event toEvent(NotificationEventJson json) {
        Instant occurredAt = Instant.parse(json.deliveryDate());
        String payload = objectMapper.writeValueAsString(
                new NotificationEventPayload(json.eventId(), json.eventType(), json.clientId(), json.content()));
        return new Event(json.eventId(), json.eventType(), json.clientId(), payload, occurredAt);
    }
}
