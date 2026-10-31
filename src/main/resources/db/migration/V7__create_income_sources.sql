-- Income sources (salary, freelance, rent...) and what is expected from them each month.
-- Expectations are plans only: income counts once an INCOME transaction is recorded.

CREATE TABLE income_sources (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL,
    type         VARCHAR(20) NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    category_id  UUID        NOT NULL REFERENCES categories (id),
    account_id   UUID        REFERENCES accounts (id),  -- usual destination; null = default account
    start_month  DATE        NOT NULL,                  -- no expectations before this month
    notes        VARCHAR(500),
    active       BOOLEAN     NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_income_sources_type CHECK (type IN
        ('SALARY', 'BONUS', 'FREELANCE', 'BUSINESS', 'RENTAL', 'INTEREST', 'GIFT', 'OTHER')),
    CONSTRAINT ck_income_sources_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_income_sources_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_income_sources_start_month CHECK (start_month = date_trunc('month', start_month)::date)
);

CREATE UNIQUE INDEX uk_income_sources_user_name ON income_sources (user_id, lower(name));

-- Expected monthly amount, effective from a month until the next row. A null amount
-- means "nothing expected from this month on". Raises don't rewrite earlier months.
CREATE TABLE income_expectations (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    income_source_id  UUID          NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    effective_month   DATE          NOT NULL,
    amount            NUMERIC(19,4),
    expected_day      SMALLINT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_income_expectations_month UNIQUE (income_source_id, effective_month),
    CONSTRAINT ck_income_expectations_month CHECK (effective_month = date_trunc('month', effective_month)::date),
    CONSTRAINT ck_income_expectations_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_income_expectations_day CHECK ((amount IS NULL) = (expected_day IS NULL)
        AND (expected_day IS NULL OR expected_day BETWEEN 1 AND 31))
);

-- An income transaction may settle a source's expectation for one pay period (a month).
ALTER TABLE transactions
    ADD COLUMN income_source_id UUID REFERENCES income_sources (id),
    ADD COLUMN income_period    DATE,
    ADD CONSTRAINT ck_transactions_income_source CHECK (income_source_id IS NULL OR type = 'INCOME'),
    ADD CONSTRAINT ck_transactions_income_period CHECK ((income_source_id IS NULL) = (income_period IS NULL)),
    ADD CONSTRAINT ck_transactions_income_period_month
        CHECK (income_period IS NULL OR income_period = date_trunc('month', income_period)::date);

CREATE INDEX ix_transactions_income_source_period ON transactions (income_source_id, income_period)
    WHERE income_source_id IS NOT NULL;
