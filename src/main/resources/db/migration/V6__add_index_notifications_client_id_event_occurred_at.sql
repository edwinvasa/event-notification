CREATE INDEX idx_notifications_client_id_event_occurred_at
    ON notifications (client_id, event_occurred_at DESC);
