CREATE TABLE delivery_attempts (
    id UUID NOT NULL,
    notification_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    duration NUMERIC(21,0) NOT NULL,
    outcome_type TEXT NOT NULL,
    http_status_code INTEGER,
    error_detail TEXT,
    response_snippet TEXT,
    url_used TEXT NOT NULL,
    trigger TEXT NOT NULL,
    CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_delivery_attempts_notification FOREIGN KEY (notification_id) REFERENCES notifications (id)
);
