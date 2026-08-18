package com.edwin.eventnotification.adapter.in.rest.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaEntity;
import com.edwin.eventnotification.adapter.out.persistence.ApiKeyJpaRepository;

/**
 * Verifies the full Spring Security wiring (header -> converter -> provider -> SecurityContext)
 * end to end. Uses a throwaway probe controller, imported only for this test, since the real
 * self-service controllers are not implemented yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiKeyAuthenticationIntegrationTest.SecuredProbeController.class)
class ApiKeyAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
    void rejectsRequestsWithoutAnApiKeyHeader() throws Exception {
        mockMvc.perform(get("/__test/secured")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestsWithAnUnknownApiKey() throws Exception {
        mockMvc.perform(get("/__test/secured").header("X-Api-Key", "not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestsWithAnInactiveApiKey() throws Exception {
        apiKeyId = UUID.randomUUID();
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(apiKeyId, "client-42", "inactive-key", false));

        mockMvc.perform(get("/__test/secured").header("X-Api-Key", "inactive-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolvesClientIdServerSideFromAValidApiKey() throws Exception {
        apiKeyId = UUID.randomUUID();
        apiKeyJpaRepository.save(new ApiKeyJpaEntity(apiKeyId, "client-42", "valid-key-123", true));

        mockMvc.perform(get("/__test/secured").header("X-Api-Key", "valid-key-123"))
                .andExpect(status().isOk())
                .andExpect(content().string("client-42"));
    }

    @RestController
    static class SecuredProbeController {

        @GetMapping("/__test/secured")
        public String whoAmI(Authentication authentication) {
            return (String) authentication.getPrincipal();
        }
    }
}
