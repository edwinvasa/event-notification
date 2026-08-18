CREATE TABLE subscriptions (
    id UUID NOT NULL,
    client_id TEXT NOT NULL,
    webhook_url TEXT NOT NULL,
    hmac_secret TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT pk_subscriptions PRIMARY KEY (id)
);
