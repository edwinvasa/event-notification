package com.edwin.eventnotification.adapter.in.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.edwin.eventnotification.adapter.in.seed.DemoSeedProperties;
import com.edwin.eventnotification.adapter.in.seed.DemoSubscriptionSeedRunner;
import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaRepository;
import com.edwin.eventnotification.adapter.out.persistence.SubscriptionJpaRepository;
import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.in.IngestEventPort;
import com.edwin.eventnotification.application.port.out.NotificationRepository;
import com.edwin.eventnotification.domain.notification.Notification;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in verification against the real {@code challenge/notification_events.json}, using
 * the exact same JSON adapter and demo seed runner as production, against real PostgreSQL.
 *
 * <p>Disabled by default: it depends on the challenge file being present at the project root and
 * is intentionally destructive/additive - it seeds real demo subscriptions/API keys for
 * CLIENT001/002/003 and leaves them (and the resulting notifications) in the database on purpose,
 * so the REST API can be explored manually afterward. Remove {@code @Disabled} locally to run it.
 */
@Disabled("Manual opt-in verification against the real challenge/notification_events.json - "
        + "seeds real demo data and leaves it in Postgres for further manual exploration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationEventsJsonIngestionRunnerManualEndToEndTest {

    @Autowired
    private IngestEventPort ingestEventPort;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Autowired
    private ApiKeyJpaRepository apiKeyJpaRepository;

    @Autowired
    private DemoSeedProperties demoSeedProperties;

    private final ObjectMapper plainMapper =
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private NotificationEventsFile readRealFile(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream()) {
            return plainMapper.readValue(inputStream, NotificationEventsFile.class);
        }
    }

    @Test
    void ingestsTheRealChallengeFileAgainstRealPostgres() throws Exception {
        Resource realFile = resourceLoader.getResource("file:challenge/notification_events.json");
        assertThat(realFile.exists())
                .as("challenge/notification_events.json must exist at the project root")
                .isTrue();

        NotificationEventsFile parsed = readRealFile(realFile);
        int eventsRead = parsed.events().size();
        System.out.println("Events read from the file: " + eventsRead);
        assertThat(eventsRead).isEqualTo(10);

        Set<String> clientIdsInFile =
                parsed.events().stream().map(NotificationEventJson::clientId).collect(Collectors.toSet());
        Set<String> clientsWithoutSeededSubscription = new HashSet<>(clientIdsInFile);
        clientsWithoutSeededSubscription.removeAll(DemoSubscriptionSeedRunner.DEMO_CLIENT_IDS);
        System.out.println("Clients in the file without a seeded subscription: " + clientsWithoutSeededSubscription);
        assertThat(clientsWithoutSeededSubscription).isEmpty();

        DemoSubscriptionSeedRunner seedRunner =
                new DemoSubscriptionSeedRunner(subscriptionJpaRepository, apiKeyJpaRepository, demoSeedProperties);
        seedRunner.seed();

        NotificationEventsJsonIngestionRunner runner =
                new NotificationEventsJsonIngestionRunner(ingestEventPort, resourceLoader, "unused", true);
        runner.ingestFrom(realFile);

        Map<String, Integer> expectedCountsByClient = Map.of("CLIENT001", 4, "CLIENT002", 3, "CLIENT003", 3);
        int totalCreated = 0;
        for (String clientId : DemoSubscriptionSeedRunner.DEMO_CLIENT_IDS) {
            List<Notification> notifications =
                    notificationRepository.findByClientId(clientId, new NotificationQueryFilter(null, null, null));
            System.out.println(clientId + ": " + notifications.size() + " notifications created");
            assertThat(notifications).hasSize(expectedCountsByClient.get(clientId));
            totalCreated += notifications.size();
        }
        System.out.println("Total notifications created: " + totalCreated);
        assertThat(totalCreated).isEqualTo(eventsRead);

        runner.ingestFrom(realFile);

        int totalAfterReprocessing = 0;
        for (String clientId : DemoSubscriptionSeedRunner.DEMO_CLIENT_IDS) {
            totalAfterReprocessing += notificationRepository
                    .findByClientId(clientId, new NotificationQueryFilter(null, null, null))
                    .size();
        }
        System.out.println("Total notifications after reprocessing the same file: " + totalAfterReprocessing);
        assertThat(totalAfterReprocessing).isEqualTo(totalCreated);
    }
}
