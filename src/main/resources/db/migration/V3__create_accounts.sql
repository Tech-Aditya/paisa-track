-- Accounts hold money (assets) or debt (credit cards). Balances are never stored:
-- they are derived from opening_balance plus active transactions.

CREATE TABLE accounts (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(60)   NOT NULL,
    type             VARCHAR(20)   NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    -- Assets: money held at opening. Credit cards: amount owed at opening.
    opening_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit_limit     NUMERIC(19,4),
    institution      VARCHAR(100),
    account_last4    VARCHAR(4),
    notes            VARCHAR(500),
    is_default       BOOLEAN       NOT NULL DEFAULT false,
    active           BOOLEAN       NOT NULL DEFAULT true,
    deactivated_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_accounts_type CHECK (type IN ('CASH', 'SAVINGS', 'CURRENT', 'CREDIT_CARD', 'WALLET', 'OTHER')),
    CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_accounts_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_accounts_last4 CHECK (account_last4 IS NULL OR account_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_accounts_credit_limit CHECK (credit_limit IS NULL OR (type = 'CREDIT_CARD' AND credit_limit > 0)),
    CONSTRAINT ck_accounts_active CHECK (active = (deactivated_at IS NULL)),
    CONSTRAINT ck_accounts_default_active CHECK (NOT is_default OR active)
);

CREATE UNIQUE INDEX uk_accounts_user_name ON accounts (user_id, lower(name));
CREATE UNIQUE INDEX uk_accounts_user_default ON accounts (user_id) WHERE is_default;

-- Existing users get the same default Cash account new users receive.
INSERT INTO accounts (user_id, name, type, currency, is_default)
SELECT u.id, 'Cash', 'CASH', p.default_currency, true
FROM users u JOIN user_preferences p ON p.user_id = u.id;
