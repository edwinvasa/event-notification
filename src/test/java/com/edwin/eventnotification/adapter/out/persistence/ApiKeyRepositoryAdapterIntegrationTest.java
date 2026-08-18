package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.edwin.eventnotification.application.port.out.ApiKeyRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApiKeyRepositoryAdapterIntegrationTest {

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyJpaRepository apiKeyJpaRepository;

    private UUID apiKeyId;

    @AfterEach
    void cleanUp() {
        if (apiKeyId != null) {
            apiKeyJpaRepository.deleteById(apiKeyId);
        }
    }

    @Test
    void resolvesTheClientIdForAnActiveApiKey() {
        apiKeyId = UUID.randomUUID();
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(apiKeyId, "client-1", "active-key-1", true));

        Optional<String> clientId = apiKeyRepository.findActiveClientIdByApiKey("active-key-1");

        assertThat(clientId).contains("client-1");
    }

    @Test
    void returnsEmptyForAnInactiveApiKey() {
        apiKeyId = UUID.randomUUID();
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(apiKeyId, "client-1", "inactive-key-1", false));

        Optional<String> clientId = apiKeyRepository.findActiveClientIdByApiKey("inactive-key-1");

        assertThat(clientId).isEmpty();
    }

    @Test
    void returnsEmptyForAnUnknownApiKey() {
        Optional<String> clientId = apiKeyRepository.findActiveClientIdByApiKey("does-not-exist");

        assertThat(clientId).isEmpty();
    }
}
