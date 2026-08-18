package com.edwin.eventnotification.adapter.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import com.edwin.eventnotification.application.port.out.ApiKeyRepository;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationProviderTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private ApiKeyAuthenticationProvider provider() {
        return new ApiKeyAuthenticationProvider(apiKeyRepository);
    }

    @Test
    void resolvesTheClientIdServerSideForAValidKey() {
        when(apiKeyRepository.findActiveClientIdByApiKey("valid-key")).thenReturn(Optional.of("client-42"));

        Authentication result = provider().authenticate(ApiKeyAuthenticationToken.unauthenticated("valid-key"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("client-42");
    }

    @Test
    void throwsBadCredentialsWhenTheKeyDoesNotResolveToAnyClient() {
        when(apiKeyRepository.findActiveClientIdByApiKey("unknown-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider().authenticate(ApiKeyAuthenticationToken.unauthenticated("unknown-key")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void supportsOnlyApiKeyAuthenticationTokens() {
        assertThat(provider().supports(ApiKeyAuthenticationToken.class)).isTrue();
        assertThat(provider().supports(Authentication.class)).isFalse();
    }
}
