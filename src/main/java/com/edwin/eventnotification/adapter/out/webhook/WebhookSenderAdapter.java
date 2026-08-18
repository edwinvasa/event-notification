package com.edwin.eventnotification.adapter.out.webhook;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.edwin.eventnotification.application.port.out.WebhookDeliveryRequest;
import com.edwin.eventnotification.application.port.out.WebhookSenderPort;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;

@Component
public class WebhookSenderAdapter implements WebhookSenderPort {

    private final SsrfSafeAddressResolver ssrfSafeAddressResolver;
    private final HmacSigner hmacSigner;
    private final Clock clock;
    private final SSLSocketFactory sslSocketFactory;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxResponseBytes;
    private final int allowedPort;
    private final int maxTextLength;

    public WebhookSenderAdapter(
            SsrfSafeAddressResolver ssrfSafeAddressResolver,
            HmacSigner hmacSigner,
            Clock clock,
            SSLSocketFactory sslSocketFactory,
            @Value("${webhook.http.connect-timeout}") Duration connectTimeout,
            @Value("${webhook.http.read-timeout}") Duration readTimeout,
            @Value("${webhook.http.max-response-bytes}") int maxResponseBytes,
            @Value("${webhook.http.allowed-port}") int allowedPort,
            @Value("${webhook.http.max-text-length}") int maxTextLength) {
        this.ssrfSafeAddressResolver = ssrfSafeAddressResolver;
        this.hmacSigner = hmacSigner;
        this.clock = clock;
        this.sslSocketFactory = sslSocketFactory;
        this.connectTimeoutMillis = (int) connectTimeout.toMillis();
        this.readTimeoutMillis = (int) readTimeout.toMillis();
        this.maxResponseBytes = maxResponseBytes;
        this.allowedPort = allowedPort;
        this.maxTextLength = maxTextLength;
    }

    @Override
    public DeliveryOutcome send(WebhookDeliveryRequest request) {
        long startNanos = System.nanoTime();
        try {
            URI uri = URI.create(request.url());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new SecurityPolicyViolationException("Only HTTPS webhook URLs are supported: " + request.url());
            }
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            if (port != allowedPort) {
                throw new SecurityPolicyViolationException(
                        "Only port " + allowedPort + " is supported for webhook delivery: " + request.url());
            }
            String hostname = uri.getHost();
            String path = buildPath(uri);

            InetAddress validatedAddress = ssrfSafeAddressResolver.resolveValidated(hostname);

            try (Socket rawSocket = new Socket()) {
                rawSocket.connect(new InetSocketAddress(validatedAddress, port), connectTimeoutMillis);
                rawSocket.setSoTimeout(readTimeoutMillis);

                try (SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(rawSocket, hostname, port, true)) {
                    SSLParameters sslParameters = sslSocket.getSSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                    sslSocket.setSSLParameters(sslParameters);
                    sslSocket.startHandshake();

                    writeRequest(sslSocket.getOutputStream(), hostname, path, request);
                    RawHttpResponse response = readResponse(sslSocket.getInputStream());

                    return buildOutcome(response, startNanos);
                }
            }
        } catch (SecurityPolicyViolationException e) {
            return outcomeFor(DeliveryOutcomeType.SECURITY_POLICY_VIOLATION, startNanos, e.getMessage());
        } catch (SocketTimeoutException e) {
            return outcomeFor(DeliveryOutcomeType.TIMEOUT, startNanos, e.getMessage());
        } catch (UnknownHostException e) {
            return outcomeFor(DeliveryOutcomeType.DNS_ERROR, startNanos, e.getMessage());
        } catch (ConnectException e) {
            return outcomeFor(DeliveryOutcomeType.CONNECTION_ERROR, startNanos, e.getMessage());
        } catch (IOException e) {
            return outcomeFor(DeliveryOutcomeType.CONNECTION_ERROR, startNanos, e.getMessage());
        }
    }

    private String buildPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        return path;
    }

    private void writeRequest(OutputStream out, String hostname, String path, WebhookDeliveryRequest request)
            throws IOException {
        byte[] bodyBytes = request.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String signature = hmacSigner.sign(request.hmacSecret(), timestamp, request.payload());

        StringBuilder headers = new StringBuilder();
        headers.append("POST ").append(path).append(" HTTP/1.1\r\n");
        headers.append("Host: ").append(hostname).append("\r\n");
        headers.append("Content-Type: text/plain; charset=utf-8\r\n");
        headers.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        headers.append("X-Webhook-Timestamp: ").append(timestamp).append("\r\n");
        headers.append("X-Webhook-Signature: ").append(signature).append("\r\n");
        headers.append("Idempotency-Key: ").append(request.idempotencyKey()).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("\r\n");

        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    private RawHttpResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        int statusCode = parseStatusCode(statusLine);

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        boolean chunked = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"));
        String body = chunked ? readChunkedBody(in) : readFixedLengthBody(in, headers.get("content-length"));

        return new RawHttpResponse(statusCode, headers, body);
    }

    private String readFixedLengthBody(InputStream in, String contentLengthHeader) throws IOException {
        long contentLength = -1;
        if (contentLengthHeader != null) {
            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException ignored) {
                contentLength = -1;
            }
        }

        long bytesToRead = contentLength >= 0 ? Math.min(contentLength, maxResponseBytes) : maxResponseBytes;
        byte[] buffer = new byte[(int) bytesToRead];
        int totalRead = 0;
        int n;
        while (totalRead < bytesToRead && (n = in.read(buffer, totalRead, (int) bytesToRead - totalRead)) != -1) {
            totalRead += n;
        }
        return new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
    }

    private String readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (body.size() < maxResponseBytes) {
            String chunkSizeLine = readLine(in);
            int semicolon = chunkSizeLine.indexOf(';');
            String sizeHex = (semicolon >= 0 ? chunkSizeLine.substring(0, semicolon) : chunkSizeLine).trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeHex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunk size: " + chunkSizeLine, e);
            }
            if (chunkSize == 0) {
                // Final chunk. Any trailer headers are ignored since the connection is closed right after.
                break;
            }
            int remaining = chunkSize;
            while (remaining > 0) {
                int allowed = maxResponseBytes - body.size();
                if (allowed <= 0) {
                    return body.toString(StandardCharsets.UTF_8);
                }
                int toRead = Math.min(remaining, allowed);
                byte[] buffer = new byte[toRead];
                int n = in.read(buffer, 0, toRead);
                if (n == -1) {
                    throw new EOFException("Connection closed while reading chunk body");
                }
                body.write(buffer, 0, n);
                remaining -= n;
            }
            readLine(in); // consume the CRLF terminating this chunk's data
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            buffer.write(b);
        }
        throw new EOFException("Connection closed while reading response");
    }

    private int parseStatusCode(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed HTTP status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed HTTP status code in status line: " + statusLine, e);
        }
    }

    private DeliveryOutcome buildOutcome(RawHttpResponse response, long startNanos) {
        long durationMillis = elapsedMillis(startNanos);
        DeliveryOutcomeType outcomeType = (response.statusCode() >= 200 && response.statusCode() < 300)
                ? DeliveryOutcomeType.SUCCESS
                : DeliveryOutcomeType.HTTP_ERROR;

        String errorDetail =
                outcomeType == DeliveryOutcomeType.SUCCESS ? null : truncate("HTTP " + response.statusCode());
        String responseSnippet = truncate(response.body());
        Duration retryAfter = parseRetryAfter(response.header("retry-after"));

        return new DeliveryOutcome(
                outcomeType, response.statusCode(), durationMillis, errorDetail, responseSnippet, retryAfter);
    }

    private Duration parseRetryAfter(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(headerValue.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DeliveryOutcome outcomeFor(DeliveryOutcomeType type, long startNanos, String message) {
        return new DeliveryOutcome(type, null, elapsedMillis(startNanos), truncate(message), null, null);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxTextLength ? text : text.substring(0, maxTextLength);
    }

    private record RawHttpResponse(int statusCode, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
