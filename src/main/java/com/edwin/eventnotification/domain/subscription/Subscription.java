package com.edwin.eventnotification.domain.subscription;

import java.util.Objects;
import java.util.UUID;

public record Subscription(
        UUID id,
        String clientId,
        String webhookUrl,
        String hmacSecret,
        boolean active) {

    public Subscription {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(webhookUrl, "webhookUrl must not be null");
        Objects.requireNonNull(hmacSecret, "hmacSecret must not be null");
    }

    @Override
    public String toString() {
        return "Subscription[id=" + id
                + ", clientId=" + clientId
                + ", webhookUrl=" + webhookUrl
                + ", hmacSecret=***"
                + ", active=" + active
                + "]";
    }
}
