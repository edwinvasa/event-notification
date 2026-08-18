package com.edwin.eventnotification.adapter.in.json;

/**
 * Raw shape of one entry in {@code notification_events.json}, mapped field-by-field via the
 * application-wide snake_case Jackson naming strategy (event_id, event_type, delivery_date,
 * delivery_status, client_id).
 */
record NotificationEventJson(
        String eventId, String eventType, String content, String deliveryDate, String deliveryStatus, String clientId) {
}
