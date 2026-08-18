package com.edwin.eventnotification.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_keys")
public class ApiKeyJpaEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(nullable = false)
    private boolean active;

    protected ApiKeyJpaEntity() {
    }

    public ApiKeyJpaEntity(UUID id, String clientId, String apiKey, boolean active) {
        this.id = id;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isActive() {
        return active;
    }
}
