package com.edwin.eventnotification.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
public class SubscriptionJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String webhookUrl;

    @Column(nullable = false)
    private String hmacSecret;

    @Column(nullable = false)
    private boolean active;

    protected SubscriptionJpaEntity() {
    }

    public SubscriptionJpaEntity(UUID id, String clientId, String webhookUrl, String hmacSecret, boolean active) {
        this.id = id;
        this.clientId = clientId;
        this.webhookUrl = webhookUrl;
        this.hmacSecret = hmacSecret;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getHmacSecret() {
        return hmacSecret;
    }

    public boolean isActive() {
        return active;
    }
}
