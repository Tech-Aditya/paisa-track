-- Audit trail of financial changes, as {field: {from, to}} JSON.
CREATE TABLE audit_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    entity_type  VARCHAR(30) NOT NULL,
    entity_id    UUID        NOT NULL,
    action       VARCHAR(20) NOT NULL,
    changes      JSONB,
    request_id   VARCHAR(64),
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at);

-- Idempotency-Key replay protection for create requests.
CREATE TABLE idempotency_keys (
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idempotency_key  VARCHAR(100) NOT NULL,
    operation        VARCHAR(40)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    resource_id      UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_created_at ON idempotency_keys (created_at);
