package com.edwin.eventnotification.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies /actuator/prometheus and /actuator/health are reachable without an API key (they are
 * not client-facing business endpoints - SecurityConfig permits them explicitly) and that the
 * custom backlog gauge is present in the scraped output.
 *
 * <p>Spring Boot Test disables metrics export by default for every @SpringBootTest (via its own
 * DisableMetricsExportContextCustomizer, to stop test runs from pushing to a real backend), which
 * makes /actuator/prometheus itself 404 unless export is explicitly re-enabled here. The
 * underlying MeterRegistry (counters/timer/gauge) is unaffected by that and is covered by the
 * other metrics tests.
 */
@SpringBootTest(properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
class MetricsEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointIsReachableWithoutAuthenticationAndExposesTheBacklogGauge() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("notification_backlog_pending");
    }

    @Test
    void healthEndpointIsReachableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
