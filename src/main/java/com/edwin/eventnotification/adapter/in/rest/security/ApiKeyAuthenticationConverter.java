package com.edwin.eventnotification.adapter.in.rest.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the raw API key from the {@value #HEADER_NAME} header. Returns {@code null} (no
 * attempt to authenticate) when the header is absent, letting Spring Security's own
 * authorization/entry-point machinery reject the request as unauthenticated.
 */
public class ApiKeyAuthenticationConverter implements AuthenticationConverter {

    static final String HEADER_NAME = "X-Api-Key";

    @Override
    public Authentication convert(HttpServletRequest request) {
        String apiKey = request.getHeader(HEADER_NAME);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return ApiKeyAuthenticationToken.unauthenticated(apiKey);
    }
}
