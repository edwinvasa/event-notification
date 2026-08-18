CREATE TABLE notifications (
    id UUID NOT NULL,
    event_id TEXT NOT NULL,
    subscription_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    event_occurred_at TIMESTAMPTZ NOT NULL,
    payload TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    last_attempted_at TIMESTAMPTZ,
    failure_reason TEXT,
    claimed_by TEXT,
    lease_expires_at TIMESTAMPTZ,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notifications_event_subscription UNIQUE (event_id, subscription_id)
);
