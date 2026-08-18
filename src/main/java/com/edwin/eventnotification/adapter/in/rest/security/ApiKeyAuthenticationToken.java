package com.edwin.eventnotification.adapter.in.rest.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentication token for the API Key scheme (ADR-008 §1 / ADR-010). Before authentication it
 * only carries the raw key as credentials; after authentication the principal is the client_id
 * resolved server-side from persistence - never a value supplied directly by the caller.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String apiKey;
    private final String clientId;

    private ApiKeyAuthenticationToken(String apiKey) {
        super(List.of());
        this.apiKey = apiKey;
        this.clientId = null;
        setAuthenticated(false);
    }

    private ApiKeyAuthenticationToken(String apiKey, String clientId, List<GrantedAuthority> authorities) {
        super(authorities);
        this.apiKey = apiKey;
        this.clientId = clientId;
        super.setAuthenticated(true);
    }

    public static ApiKeyAuthenticationToken unauthenticated(String apiKey) {
        return new ApiKeyAuthenticationToken(apiKey);
    }

    public static ApiKeyAuthenticationToken authenticated(String apiKey, String clientId) {
        return new ApiKeyAuthenticationToken(apiKey, clientId, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return clientId;
    }
}
