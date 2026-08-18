package com.edwin.eventnotification.adapter.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import jakarta.servlet.http.HttpServletRequest;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationConverterTest {

    @Mock
    private HttpServletRequest request;

    private final ApiKeyAuthenticationConverter converter = new ApiKeyAuthenticationConverter();

    @Test
    void returnsAnUnauthenticatedTokenCarryingTheRawKeyWhenHeaderIsPresent() {
        when(request.getHeader("X-Api-Key")).thenReturn("raw-key-123");

        Authentication authentication = converter.convert(request);

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isFalse();
        assertThat(authentication.getCredentials()).isEqualTo("raw-key-123");
    }

    @Test
    void returnsNullWhenHeaderIsAbsent() {
        when(request.getHeader("X-Api-Key")).thenReturn(null);

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    void returnsNullWhenHeaderIsBlank() {
        when(request.getHeader("X-Api-Key")).thenReturn("   ");

        assertThat(converter.convert(request)).isNull();
    }
}
