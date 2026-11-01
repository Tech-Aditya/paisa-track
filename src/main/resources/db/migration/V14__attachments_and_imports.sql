-- Receipts attached to transactions. The file itself lives in private storage under
-- storage_key (never derived from the user's file name); this row is the metadata.
CREATE TABLE attachments (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    transaction_id  UUID          NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    file_name       VARCHAR(200)  NOT NULL,
    content_type    VARCHAR(100)  NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    sha256          VARCHAR(64)   NOT NULL,
    storage_key     VARCHAR(200)  NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_attachments_size CHECK (size_bytes > 0),
    CONSTRAINT ck_attachments_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf'))
);

CREATE INDEX ix_attachments_transaction ON attachments (transaction_id);

-- One row per confirmed statement import, so imports can be reviewed later.
CREATE TABLE import_batches (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id     UUID          NOT NULL REFERENCES accounts (id),
    file_name      VARCHAR(200),
    rows_imported  INT           NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_import_batches_rows CHECK (rows_imported >= 0)
);

CREATE INDEX ix_import_batches_user ON import_batches (user_id, created_at DESC);
