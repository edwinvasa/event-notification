package com.edwin.eventnotification.adapter.in.rest.security;

import java.util.Objects;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import com.edwin.eventnotification.application.port.out.ApiKeyRepository;

public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationProvider(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = Objects.requireNonNull(apiKeyRepository, "apiKeyRepository must not be null");
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String apiKey = (String) authentication.getCredentials();
        String clientId = apiKeyRepository
                .findActiveClientIdByApiKey(apiKey)
                .orElseThrow(() -> new BadCredentialsException("Invalid API key"));
        return ApiKeyAuthenticationToken.authenticated(apiKey, clientId);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
