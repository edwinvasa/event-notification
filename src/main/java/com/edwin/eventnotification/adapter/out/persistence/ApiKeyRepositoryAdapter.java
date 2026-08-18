package com.edwin.eventnotification.adapter.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.edwin.eventnotification.application.port.out.ApiKeyRepository;

@Repository
public class ApiKeyRepositoryAdapter implements ApiKeyRepository {

    private final ApiKeyJpaRepository apiKeyJpaRepository;

    public ApiKeyRepositoryAdapter(ApiKeyJpaRepository apiKeyJpaRepository) {
        this.apiKeyJpaRepository = apiKeyJpaRepository;
    }

    @Override
    public Optional<String> findActiveClientIdByApiKey(String apiKey) {
        return apiKeyJpaRepository.findByApiKeyAndActiveTrue(apiKey).map(ApiKeyJpaEntity::getClientId);
    }
}
