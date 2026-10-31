-- Recurring rules (rent, bills, subscriptions, SIPs) and their expected occurrences.
-- An occurrence is a plan, never a transaction: nothing reaches balances or totals until the
-- user confirms it, which records a normal transaction. Salary and EMIs are not recurring rules;
-- income sources and loans already track them.

CREATE TABLE recurring_rules (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(80)   NOT NULL,
    kind            VARCHAR(20)   NOT NULL,
    type            VARCHAR(10)   NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,          -- expected amount; the confirmed one may differ
    currency        VARCHAR(3)    NOT NULL,
    account_id      UUID          NOT NULL REFERENCES accounts (id),
    to_account_id   UUID          REFERENCES accounts (id),
    category_id     UUID          REFERENCES categories (id),
    payment_method  VARCHAR(20),
    counterparty    VARCHAR(100),                    -- merchant or payer
    description     VARCHAR(200),
    notes           VARCHAR(1000),
    frequency       VARCHAR(10)   NOT NULL,
    interval_count  INT           NOT NULL DEFAULT 1, -- every N days/weeks/months/quarters/years
    start_date      DATE          NOT NULL,          -- first due date; anchors the schedule
    end_date        DATE,
    status          VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    next_index      INT           NOT NULL DEFAULT 0, -- next occurrence number not yet generated
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_recurring_rules_kind CHECK (kind IN ('RENT', 'BILL', 'SUBSCRIPTION', 'INSURANCE', 'INVESTMENT', 'OTHER')),
    CONSTRAINT ck_recurring_rules_type CHECK (type IN ('EXPENSE', 'INCOME', 'TRANSFER')),
    CONSTRAINT ck_recurring_rules_shape CHECK (
        (type = 'TRANSFER' AND to_account_id IS NOT NULL AND to_account_id <> account_id AND category_id IS NULL)
        OR (type IN ('EXPENSE', 'INCOME') AND to_account_id IS NULL AND category_id IS NOT NULL)),
    CONSTRAINT ck_recurring_rules_amount CHECK (amount > 0),
    CONSTRAINT ck_recurring_rules_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_recurring_rules_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    CONSTRAINT ck_recurring_rules_interval CHECK (interval_count BETWEEN 1 AND 99),
    CONSTRAINT ck_recurring_rules_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_recurring_rules_status CHECK (status IN ('ACTIVE', 'PAUSED', 'STOPPED')),
    CONSTRAINT ck_recurring_rules_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_recurring_rules_payment_method CHECK (payment_method IS NULL OR payment_method IN
        ('CASH', 'UPI', 'DEBIT_CARD', 'CREDIT_CARD', 'NET_BANKING', 'WALLET', 'CHEQUE', 'OTHER'))
);

CREATE INDEX ix_recurring_rules_user ON recurring_rules (user_id);
CREATE INDEX ix_recurring_rules_active ON recurring_rules (user_id) WHERE status = 'ACTIVE';

-- One row per due date. The unique key makes generation idempotent across retries,
-- restarts and parallel scheduler runs.
CREATE TABLE recurring_occurrences (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rule_id          UUID          NOT NULL REFERENCES recurring_rules (id) ON DELETE CASCADE,
    due_date         DATE          NOT NULL,
    expected_amount  NUMERIC(19,4) NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'EXPECTED',
    transaction_id   UUID          REFERENCES transactions (id),   -- the actual transaction (kept if it is voided,
                                                                   -- so restoring it confirms the occurrence again)
    actual_amount    NUMERIC(19,4),
    resolved_at      TIMESTAMPTZ,
    note             VARCHAR(200),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_recurring_occurrences_due UNIQUE (rule_id, due_date),
    CONSTRAINT ck_recurring_occurrences_status CHECK (status IN ('EXPECTED', 'CONFIRMED', 'SKIPPED')),
    CONSTRAINT ck_recurring_occurrences_shape CHECK ((status <> 'CONFIRMED' OR transaction_id IS NOT NULL)
        AND (status = 'CONFIRMED') = (actual_amount IS NOT NULL)
        AND (status = 'EXPECTED') = (resolved_at IS NULL))
);

CREATE INDEX ix_recurring_occurrences_user_due ON recurring_occurrences (user_id, due_date);
CREATE INDEX ix_recurring_occurrences_transaction ON recurring_occurrences (transaction_id) WHERE transaction_id IS NOT NULL;

-- In-app notifications. dedupe_key identifies one reminder for one item at one stage, so
-- repeated or parallel job runs never notify twice.
CREATE TABLE notifications (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type          VARCHAR(20)   NOT NULL,
    stage         VARCHAR(10)   NOT NULL,
    title         VARCHAR(120)  NOT NULL,
    message       VARCHAR(500)  NOT NULL,
    subject_id    UUID,                              -- loan, debt, budget, income source or occurrence
    due_date      DATE,
    link          VARCHAR(100),                      -- web app route, e.g. #/loans
    dedupe_key    VARCHAR(200)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    CONSTRAINT uk_notifications_dedupe UNIQUE (user_id, dedupe_key),
    CONSTRAINT ck_notifications_type CHECK (type IN
        ('EMI', 'BILL', 'SUBSCRIPTION', 'RECURRING', 'LENDING', 'BORROWING', 'BUDGET', 'SALARY')),
    CONSTRAINT ck_notifications_stage CHECK (stage IN ('UPCOMING', 'DUE', 'OVERDUE', 'ALERT'))
);

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Per-type preferences. A missing row means the defaults: enabled, 3 days before.
CREATE TABLE notification_preferences (
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(20)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    days_before  INT          NOT NULL DEFAULT 3,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, type),
    CONSTRAINT ck_notification_preferences_type CHECK (type IN
        ('EMI', 'BILL', 'SUBSCRIPTION', 'RECURRING', 'LENDING', 'BORROWING', 'BUDGET', 'SALARY')),
    CONSTRAINT ck_notification_preferences_days CHECK (days_before BETWEEN 0 AND 30)
);
