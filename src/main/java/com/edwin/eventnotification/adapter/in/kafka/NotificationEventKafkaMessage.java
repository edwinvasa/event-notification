package com.edwin.eventnotification.adapter.in.kafka;

/**
 * Raw shape of a Kafka message on the notification events topic, mapped field-by-field via the
 * dedicated snake_case ObjectMapper (event_id, event_type, client_id, content, delivery_date).
 * Deliberately has no delivery_status field - the topic's message contract never carries it.
 */
record NotificationEventKafkaMessage(String eventId, String eventType, String clientId, String content, String deliveryDate) {
}
