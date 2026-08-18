ALTER TABLE api_keys
    ADD CONSTRAINT uq_api_keys_client_id UNIQUE (client_id);
