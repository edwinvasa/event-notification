package com.edwin.eventnotification.adapter.in.kafka;

/**
 * Structured payload frozen onto the resulting Notification (and eventually sent as the webhook
 * body). Mirrors the JSON adapter's own payload shape but is kept local to this package - sibling
 * input adapters do not depend on each other's internal DTOs.
 */
record NotificationEventPayload(String eventId, String eventType, String clientId, String content) {
}
