package com.edwin.eventnotification.adapter.in.seed;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Demo/bootstrap-only configuration for {@link DemoSubscriptionSeedRunner}. Not a business
 * capability - just a typed binding for {@code demo.seed.*}, including a per-client_id map of
 * webhook URLs ({@code demo.seed.webhook-urls.CLIENT001}, etc.) since a Map property cannot be
 * bound with a plain {@code @Value}.
 */
@Component
@ConfigurationProperties(prefix = "demo.seed")
public class DemoSeedProperties {

    private boolean enabled;
    private Map<String, String> webhookUrls = Map.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getWebhookUrls() {
        return webhookUrls;
    }

    public void setWebhookUrls(Map<String, String> webhookUrls) {
        this.webhookUrls = webhookUrls;
    }
}
