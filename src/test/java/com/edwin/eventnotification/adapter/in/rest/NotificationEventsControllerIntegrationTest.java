package com.edwin.eventnotification.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaEntity;
import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaRepository;
import com.edwin.eventnotification.adapter.out.persistence.NotificationJpaRepository;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationEventsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    @Autowired
    private ApiKeyJpaRepository apiKeyJpaRepository;

    private final List<UUID> apiKeyIds = new ArrayList<>();
    private final List<UUID> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        notificationIds.forEach(notificationJpaRepository::deleteById);
        notificationIds.clear();
        apiKeyIds.forEach(apiKeyJpaRepository::deleteById);
        apiKeyIds.clear();
    }

    private record TestClient(String clientId, String apiKey) {
    }

    private TestClient newClient() {
        String clientId = "client-" + UUID.randomUUID();
        String apiKey = "key-" + UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        apiKeyIds.add(apiKeyId);
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(apiKeyId, clientId, apiKey, true));
        return new TestClient(clientId, apiKey);
    }

    private UUID seedNotification(String clientId, NotificationStatus status, Instant eventOccurredAt) {
        UUID id = UUID.randomUUID();
        notificationIds.add(id);
        Notification notification = Notification.restore(
                id,
                "evt-" + id,
                UUID.randomUUID(),
                clientId,
                eventOccurredAt,
                "payload-" + id,
                status,
                1,
                null,
                eventOccurredAt.plusSeconds(5),
                status == NotificationStatus.FAILED ? FailureReason.PERMANENT_ERROR : null,
                null,
                null);
        notificationRepository.saveIdempotent(notification);
        return id;
    }

    @Test
    void listReturnsOnlyNotificationsBelongingToTheAuthenticatedClient() throws Exception {
        TestClient client = newClient();
        TestClient other = newClient();
        seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));
        seedNotification(other.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(get("/notification_events").header("X-Api-Key", client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listResponseExcludesThePayload() throws Exception {
        TestClient client = newClient();
        seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(get("/notification_events").header("X-Api-Key", client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payload").doesNotExist())
                .andExpect(jsonPath("$[0].notification_id").exists());
    }

    @Test
    void listFiltersByStatus() throws Exception {
        TestClient client = newClient();
        seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));
        seedNotification(client.clientId(), NotificationStatus.FAILED, Instant.parse("2026-01-02T00:00:00Z"));

        mockMvc.perform(get("/notification_events").header("X-Api-Key", client.apiKey()).param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void listFiltersByCreatedDateRange() throws Exception {
        TestClient client = newClient();
        seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));
        UUID inRange =
                seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-03-01T00:00:00Z"));

        mockMvc.perform(get("/notification_events")
                        .header("X-Api-Key", client.apiKey())
                        .param("created_from", "2026-02-01T00:00:00Z")
                        .param("created_to", "2026-04-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].notification_id").value(inRange.toString()));
    }

    @Test
    void listRejectsAnInvertedDateRangeWith400() throws Exception {
        TestClient client = newClient();

        mockMvc.perform(get("/notification_events")
                        .header("X-Api-Key", client.apiKey())
                        .param("created_from", "2026-02-01T00:00:00Z")
                        .param("created_to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWithoutAnApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/notification_events")).andExpect(status().isUnauthorized());
    }

    @Test
    void detailReturnsTheFullNotificationIncludingPayload() throws Exception {
        TestClient client = newClient();
        UUID notificationId =
                seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(get("/notification_events/" + notificationId).header("X-Api-Key", client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification_id").value(notificationId.toString()))
                .andExpect(jsonPath("$.payload").value("payload-" + notificationId));
    }

    @Test
    void detailReturns404ForAnUnknownNotification() throws Exception {
        TestClient client = newClient();

        mockMvc.perform(get("/notification_events/" + UUID.randomUUID()).header("X-Api-Key", client.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailReturns404ForANotificationBelongingToAnotherClient() throws Exception {
        TestClient owner = newClient();
        TestClient intruder = newClient();
        UUID notificationId =
                seedNotification(owner.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(get("/notification_events/" + notificationId).header("X-Api-Key", intruder.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void replaySucceedsForAFailedNotificationAndTransitionsItToPending() throws Exception {
        TestClient client = newClient();
        UUID notificationId =
                seedNotification(client.clientId(), NotificationStatus.FAILED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(post("/notification_events/" + notificationId + "/replay").header("X-Api-Key", client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempt_count").value(1));

        Notification persisted = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void replayReturns409WhenTheNotificationIsNotFailed() throws Exception {
        TestClient client = newClient();
        UUID notificationId =
                seedNotification(client.clientId(), NotificationStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(post("/notification_events/" + notificationId + "/replay").header("X-Api-Key", client.apiKey()))
                .andExpect(status().isConflict());
    }

    @Test
    void replayReturns404WhenTheNotificationBelongsToAnotherClient() throws Exception {
        TestClient owner = newClient();
        TestClient intruder = newClient();
        UUID notificationId =
                seedNotification(owner.clientId(), NotificationStatus.FAILED, Instant.parse("2026-01-01T00:00:00Z"));

        mockMvc.perform(
                        post("/notification_events/" + notificationId + "/replay").header("X-Api-Key", intruder.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayReturns429WithRetryAfterHeaderWhenTheRateLimitIsExceeded() throws Exception {
        TestClient client = newClient();

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post("/notification_events/" + UUID.randomUUID() + "/replay")
                            .header("X-Api-Key", client.apiKey()))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(post("/notification_events/" + UUID.randomUUID() + "/replay").header("X-Api-Key", client.apiKey()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
