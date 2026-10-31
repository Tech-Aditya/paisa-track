-- Recorded money movements. EXPENSE and INCOME carry a category; TRANSFER moves money
-- between two of the user's accounts and is never income or expense.
-- Deleting a transaction voids it: it stays for history but leaves all totals.

CREATE TABLE transactions (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type              VARCHAR(10)   NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    account_id        UUID          NOT NULL REFERENCES accounts (id),  -- paid from / received into / transfer source
    to_account_id     UUID          REFERENCES accounts (id),           -- transfer destination
    category_id       UUID          REFERENCES categories (id),
    transaction_date  DATE          NOT NULL,
    transaction_time  TIME,
    description       VARCHAR(200),
    counterparty      VARCHAR(100),  -- merchant for expenses, payer for income
    payment_method    VARCHAR(20),
    notes             VARCHAR(1000),
    voided_at         TIMESTAMPTZ,
    void_reason       VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_transactions_type CHECK (type IN ('EXPENSE', 'INCOME', 'TRANSFER')),
    CONSTRAINT ck_transactions_status CHECK (status IN ('ACTIVE', 'VOIDED')),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_transactions_shape CHECK (
        (type = 'TRANSFER' AND to_account_id IS NOT NULL AND to_account_id <> account_id AND category_id IS NULL)
        OR (type <> 'TRANSFER' AND to_account_id IS NULL AND category_id IS NOT NULL)),
    CONSTRAINT ck_transactions_voided CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    CONSTRAINT ck_transactions_payment_method CHECK (payment_method IS NULL OR payment_method IN
        ('CASH', 'UPI', 'DEBIT_CARD', 'CREDIT_CARD', 'NET_BANKING', 'WALLET', 'CHEQUE', 'OTHER'))
);

CREATE INDEX ix_transactions_user_date ON transactions (user_id, transaction_date DESC, created_at DESC);
CREATE INDEX ix_transactions_account_date ON transactions (account_id, transaction_date);
CREATE INDEX ix_transactions_to_account_date ON transactions (to_account_id, transaction_date) WHERE to_account_id IS NOT NULL;
CREATE INDEX ix_transactions_user_category ON transactions (user_id, category_id);
