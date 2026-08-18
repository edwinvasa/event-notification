package com.edwin.eventnotification.adapter.in.json;

/**
 * Structured payload frozen onto the resulting Notification (and eventually sent as the webhook
 * body). Deliberately excludes {@code delivery_date} (already captured as {@code occurredAt}) and
 * {@code delivery_status} (the input file's own pre-existing dummy status - this system computes
 * its own delivery status via Notification's state machine).
 */
record NotificationEventPayload(String eventId, String eventType, String clientId, String content) {
}
