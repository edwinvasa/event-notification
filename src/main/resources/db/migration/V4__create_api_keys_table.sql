CREATE TABLE api_keys (
    id UUID NOT NULL,
    client_id TEXT NOT NULL,
    api_key TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT pk_api_keys PRIMARY KEY (id),
    CONSTRAINT uq_api_keys_api_key UNIQUE (api_key)
);
