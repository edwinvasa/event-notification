package com.edwin.eventnotification.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.edwin.eventnotification.application.port.out.WebhookDeliveryRequest;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;

@ExtendWith(MockitoExtension.class)
class WebhookSenderAdapterTest {

    private static final String CERT_HOSTNAME = "test-hostname.local";
    private static SSLContext serverSslContext;
    private static SSLSocketFactory trustingClientSslSocketFactory;

    @Mock
    private SsrfSafeAddressResolver ssrfSafeAddressResolver;

    private HttpsServer runningServer;

    @BeforeAll
    static void loadTestCertificate() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = WebhookSenderAdapterTest.class.getResourceAsStream("/webhook-test-keystore.p12")) {
            ks.load(in, "changeit".toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
        trustingClientSslSocketFactory = clientContext.getSocketFactory();
    }

    @AfterEach
    void stopServer() {
        if (runningServer != null) {
            runningServer.stop(0);
            runningServer = null;
        }
    }

    private HttpsServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverSslContext.getDefaultSSLParameters());
            }
        });
        server.createContext("/webhook", handler);
        server.start();
        runningServer = server;
        return server;
    }

    private WebhookSenderAdapter adapterTrustingTestCert(
            SSLSocketFactory sslSocketFactory, int allowedPort, Duration connectTimeout, Duration readTimeout) {
        return newAdapter(sslSocketFactory, allowedPort, connectTimeout, readTimeout, 8192);
    }

    private WebhookSenderAdapter newAdapter(
            SSLSocketFactory sslSocketFactory,
            int allowedPort,
            Duration connectTimeout,
            Duration readTimeout,
            int maxResponseBytes) {
        return new WebhookSenderAdapter(
                ssrfSafeAddressResolver,
                new HmacSigner(),
                Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                sslSocketFactory,
                connectTimeout,
                readTimeout,
                maxResponseBytes,
                allowedPort,
                500);
    }

    private void stubResolverToLoopback() throws Exception {
        when(ssrfSafeAddressResolver.resolveValidated(any())).thenReturn(InetAddress.getByName("127.0.0.1"));
    }

    private WebhookDeliveryRequest requestFor(int port, String payload) {
        return new WebhookDeliveryRequest(
                "https://" + CERT_HOSTNAME + ":" + port + "/webhook", payload, "idem-key-123", "shared-secret");
    }

    @Test
    void twoHundredResponseIsSuccess() throws Exception {
        HttpsServer server = startServer(exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
        assertThat(outcome.httpStatusCode()).isEqualTo(200);
    }

    @Test
    void redirectIsReportedAsHttpErrorWithoutBeingFollowed() throws Exception {
        HttpsServer server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://attacker.example.com/steal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.HTTP_ERROR);
        assertThat(outcome.httpStatusCode()).isEqualTo(302);
    }

    @Test
    void clientErrorIsHttpError() throws Exception {
        HttpsServer server = startServer(exchange -> {
            byte[] body = "not found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.HTTP_ERROR);
        assertThat(outcome.httpStatusCode()).isEqualTo(404);
    }

    @Test
    void serverErrorIsHttpError() throws Exception {
        HttpsServer server = startServer(exchange -> {
            byte[] body = "boom".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.HTTP_ERROR);
        assertThat(outcome.httpStatusCode()).isEqualTo(500);
    }

    @Test
    void numericRetryAfterIsParsed() throws Exception {
        HttpsServer server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            byte[] body = "unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nonNumericRetryAfterBecomesNull() throws Exception {
        HttpsServer server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
            byte[] body = "unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.retryAfter()).isNull();
    }

    @Test
    void sendsCorrectHmacSignatureAndIdempotencyKey() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> receivedSignature = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> receivedTimestamp = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> receivedIdempotencyKey = new java.util.concurrent.atomic.AtomicReference<>();

        HttpsServer server = startServer(exchange -> {
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
            receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        adapter.send(requestFor(server.getAddress().getPort(), "hello-body"));

        assertThat(receivedIdempotencyKey.get()).isEqualTo("idem-key-123");
        assertThat(receivedTimestamp.get()).isEqualTo("1767225600");

        String expectedSignature = new HmacSigner().sign("shared-secret", receivedTimestamp.get(), "hello-body");
        assertThat(receivedSignature.get()).isEqualTo(expectedSignature);
    }

    @Test
    void hostnameMismatchAgainstCertificateFailsTlsVerification() throws Exception {
        HttpsServer server = startServer(exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        WebhookDeliveryRequest wrongHostnameRequest = new WebhookDeliveryRequest(
                "https://wrong-hostname.local:" + server.getAddress().getPort() + "/webhook",
                "hello", "idem-key", "secret");

        DeliveryOutcome outcome = adapter.send(wrongHostnameRequest);

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.CONNECTION_ERROR);
    }

    @Test
    void loopbackHostnameIsRejectedWithoutAttemptingConnection() {
        SsrfSafeAddressResolver realResolver = new SsrfSafeAddressResolver();
        WebhookSenderAdapter adapter = new WebhookSenderAdapter(
                realResolver,
                new HmacSigner(),
                Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                trustingClientSslSocketFactory,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                8192,
                4443,
                500);

        WebhookDeliveryRequest requestToLoopback =
                new WebhookDeliveryRequest("https://localhost:4443/webhook", "hello", "idem-key", "secret");

        DeliveryOutcome outcome = adapter.send(requestToLoopback);

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.SECURITY_POLICY_VIOLATION);
    }

    @Test
    void readTimeoutMapsToTimeoutOutcome() throws Exception {
        HttpsServer server = startServer(exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes();
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // client will have already timed out and closed
            }
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofMillis(100));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.TIMEOUT);
    }

    @Test
    void connectionRefusedMapsToConnectionError() throws Exception {
        int unusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }
        // probe is now closed, so nothing listens on unusedPort -> immediate, deterministic refusal.
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, unusedPort, Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(unusedPort, "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.CONNECTION_ERROR);
    }

    @Test
    void chunkedResponseLargerThanLimitIsCappedAndDoesNotHang() throws Exception {
        int hugeSize = 200_000;
        HttpsServer server = startServer(exchange -> {
            byte[] body = new byte[hugeSize];
            java.util.Arrays.fill(body, (byte) 'A');
            exchange.sendResponseHeaders(200, 0); // 0 => chunked, no Content-Length advertised
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter smallCapAdapter =
                newAdapter(trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5), 100);

        DeliveryOutcome outcome = smallCapAdapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
        assertThat(outcome.responseSnippet()).isNotNull();
        assertThat(outcome.responseSnippet().length()).isLessThanOrEqualTo(100);
        // Proves the chunk framing (hex size markers, CRLFs) was actually decoded away, not just
        // raw-capped: every captured character is genuine body content, none of it is chunk metadata.
        assertThat(outcome.responseSnippet()).matches("A+");
    }

    @Test
    void chunkedResponseIsDecodedToItsExactPlainContent() throws Exception {
        HttpsServer server = startServer(exchange -> {
            // Force multiple small chunks by flushing between writes.
            exchange.sendResponseHeaders(200, 0);
            java.io.OutputStream body = exchange.getResponseBody();
            body.write("hello-".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.flush();
            body.write("chunked-".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.flush();
            body.write("world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        stubResolverToLoopback();
        WebhookSenderAdapter adapter = adapterTrustingTestCert(
                trustingClientSslSocketFactory, server.getAddress().getPort(), Duration.ofSeconds(5), Duration.ofSeconds(5));

        DeliveryOutcome outcome = adapter.send(requestFor(server.getAddress().getPort(), "hello"));

        assertThat(outcome.outcomeType()).isEqualTo(DeliveryOutcomeType.SUCCESS);
        assertThat(outcome.responseSnippet()).isEqualTo("hello-chunked-world");
    }
}
