package com.edwin.eventnotification.application.port.out;

public record WebhookDeliveryRequest(
        String url,
        String payload,
        String idempotencyKey,
        String hmacSecret) {
}
