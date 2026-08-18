package com.edwin.eventnotification.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.edwin.eventnotification.application.port.out.WebhookDeliveryRequest;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;

/**
 * Manual, opt-in verification against a real public HTTPS endpoint, using the exact same
 * configuration WebhookSenderAdapter uses in production: the JVM's real default trust store
 * (public CAs), the real (unmocked) SsrfSafeAddressResolver, and port 443 only.
 *
 * Disabled by default: it depends on real internet access and a third-party service, so it is
 * intentionally excluded from the regular deterministic test suite. Remove @Disabled locally to
 * run it against https://postman-echo.com/post, or change the URL to your own subscription
 * endpoint (e.g. a webhook.site URL) to validate that specific destination.
 */
@Disabled("Manual opt-in verification against a real public HTTPS endpoint - requires internet access")
class WebhookSenderAdapterManualEndToEndTest {

    @Test
    void deliversSuccessfullyToARealPublicHttpsEndpoint() {
        WebhookSenderAdapter adapter = new WebhookSenderAdapter(
                new SsrfSafeAddressResolver(),
                new HmacSigner(),
                Clock.systemUTC(),
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                8192,
                443,
                500);

        WebhookDeliveryRequest request = new WebhookDeliveryRequest(
                "https://webhook.site/2ae50314-a3ad-475e-8dff-e393fbf50982",
                "{\"event\":\"manual-e2e-check\"}",
                "manual-e2e-idem-key",
                "manual-e2e-secret");

        DeliveryOutcome outcome = adapter.send(request);

        // The response body itself is receiver-specific (some endpoints echo the request back,
        // others - like webhook.site by default - return a fixed acknowledgement page instead),
        // so it is printed for manual inspection rather than asserted on.
        System.out.println("Response snippet: " + outcome.responseSnippet());

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
        assertThat(outcome.httpStatusCode()).isEqualTo(200);
        //with postman:
        //assertThat(outcome.responseSnippet()).contains("manual-e2e-check");
    }
}
