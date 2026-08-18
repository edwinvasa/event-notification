package com.edwin.eventnotification.adapter.in.seed;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaEntity;
import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaRepository;
import com.edwin.eventnotification.adapter.out.persistence.SubscriptionJpaEntity;
import com.edwin.eventnotification.adapter.out.persistence.SubscriptionJpaRepository;

/**
 * Seeds an active Subscription and API Key for CLIENT001/002/003 - the client ids present in the
 * challenge's {@code notification_events.json} - so the JSON ingestion adapter and the
 * self-service REST API can be exercised against real demo data.
 *
 * <p>This is deliberately a bootstrap/infrastructure concern, not an application capability:
 * there is no port for creating subscriptions or API keys (none is required by the challenge), so
 * this runner writes directly through the JPA repositories rather than inventing a new port only
 * to support a one-time demo seed.
 *
 * <p>Disabled by default ({@code demo.seed.enabled=false}); the webhook URL per client_id
 * ({@code demo.seed.webhook-urls.CLIENT001}, etc., backed by env vars with a safe placeholder
 * default) and the generated secret/API key values are deliberately obvious demo placeholders,
 * never real credentials. Idempotent: re-running it (e.g. on every restart during a demo) skips
 * any client whose API key already exists, and only touches a subscription's row when its
 * webhook_url actually needs to change - it never duplicates a subscription.
 */
@Component
@Order(1)
public class DemoSubscriptionSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSubscriptionSeedRunner.class);

    public static final List<String> DEMO_CLIENT_IDS = List.of("CLIENT001", "CLIENT002", "CLIENT003");

    private final SubscriptionJpaRepository subscriptionJpaRepository;
    private final ApiKeyJpaRepository apiKeyJpaRepository;
    private final DemoSeedProperties demoSeedProperties;

    public DemoSubscriptionSeedRunner(
            SubscriptionJpaRepository subscriptionJpaRepository,
            ApiKeyJpaRepository apiKeyJpaRepository,
            DemoSeedProperties demoSeedProperties) {
        this.subscriptionJpaRepository =
                Objects.requireNonNull(subscriptionJpaRepository, "subscriptionJpaRepository must not be null");
        this.apiKeyJpaRepository = Objects.requireNonNull(apiKeyJpaRepository, "apiKeyJpaRepository must not be null");
        this.demoSeedProperties = Objects.requireNonNull(demoSeedProperties, "demoSeedProperties must not be null");
    }

    @Override
    public void run(String... args) {
        if (!demoSeedProperties.isEnabled()) {
            log.info("Demo subscription seeding is disabled (demo.seed.enabled=false); skipping.");
            return;
        }
        seed();
    }

    public void seed() {
        for (String clientId : DEMO_CLIENT_IDS) {
            String webhookUrl = demoSeedProperties.getWebhookUrls().get(clientId);
            if (webhookUrl == null || webhookUrl.isBlank()) {
                log.warn("No demo.seed.webhook-urls.{} configured; skipping seed for {}.", clientId, clientId);
                continue;
            }
            seedSubscription(clientId, webhookUrl);
            seedApiKey(clientId);
        }
    }

    private void seedSubscription(String clientId, String webhookUrl) {
        List<SubscriptionJpaEntity> existing = subscriptionJpaRepository.findByClientIdAndActiveTrue(clientId);

        if (existing.isEmpty()) {
            subscriptionJpaRepository.save(
                    new SubscriptionJpaEntity(UUID.randomUUID(), clientId, webhookUrl, demoHmacSecret(clientId), true));
            log.info("Seeded demo subscription for {} -> {}", clientId, webhookUrl);
            return;
        }

        SubscriptionJpaEntity current = existing.get(0);
        if (webhookUrl.equals(current.getWebhookUrl())) {
            log.info("Demo subscription for {} already points to {}; skipping.", clientId, webhookUrl);
            return;
        }

        subscriptionJpaRepository.save(
                new SubscriptionJpaEntity(current.getId(), clientId, webhookUrl, demoHmacSecret(clientId), true));
        log.info("Updated demo subscription webhook_url for {}: {} -> {}", clientId, current.getWebhookUrl(), webhookUrl);
    }

    private void seedApiKey(String clientId) {
        if (apiKeyJpaRepository.existsByClientId(clientId)) {
            log.info("Demo API key for {} already exists; skipping.", clientId);
            return;
        }
        String apiKey = demoApiKey(clientId);
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(UUID.randomUUID(), clientId, apiKey, true));
        log.info("Seeded demo API key for {}: X-Api-Key: {}", clientId, apiKey);
    }

    private static String demoHmacSecret(String clientId) {
        return "demo-secret-" + clientId;
    }

    private static String demoApiKey(String clientId) {
        return "demo-key-" + clientId;
    }
}
