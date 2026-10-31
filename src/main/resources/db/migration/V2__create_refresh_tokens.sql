-- Refresh tokens are opaque random values; only their SHA-256 hash is stored.
-- Tokens issued from one login share a family_id so a replayed (already rotated)
-- token can revoke every token descended from the same login.

CREATE TABLE refresh_tokens (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id           UUID        NOT NULL,
    token_hash          VARCHAR(64) NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    revocation_reason   VARCHAR(30),
    replaced_by_id      UUID REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT ck_refresh_tokens_reason CHECK (revocation_reason IS NULL OR revocation_reason IN
        ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'ACCOUNT_DISABLED'))
);

CREATE INDEX ix_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);
