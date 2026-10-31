-- Money lent to and borrowed from people. These are tracked on their own: they never
-- touch account balances and are never income or expense. Outstanding amounts are
-- derived (principal - active repayments - written-off amount), never stored.

CREATE TABLE people (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(80)  NOT NULL,
    phone       VARCHAR(20),
    email       VARCHAR(254),
    notes       VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_people_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uk_people_user_name ON people (user_id, lower(name));

CREATE TABLE debts (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    person_id           UUID          NOT NULL REFERENCES people (id),
    direction           VARCHAR(10)   NOT NULL,          -- LENT: they owe you; BORROWED: you owe them
    principal           NUMERIC(19,4) NOT NULL,          -- original amount; repayments never change it
    currency            VARCHAR(3)    NOT NULL,
    start_date          DATE          NOT NULL,
    due_date            DATE,
    remind_on           DATE,                            -- for the notifications phase
    description         VARCHAR(200),
    notes               VARCHAR(1000),
    payment_method      VARCHAR(20),
    written_off_amount  NUMERIC(19,4),                   -- unpaid balance when closed without full repayment
    written_off_at      TIMESTAMPTZ,
    write_off_reason    VARCHAR(200),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_debts_direction CHECK (direction IN ('LENT', 'BORROWED')),
    CONSTRAINT ck_debts_principal CHECK (principal > 0),
    CONSTRAINT ck_debts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_debts_due_date CHECK (due_date IS NULL OR due_date >= start_date),
    CONSTRAINT ck_debts_write_off CHECK ((written_off_amount IS NULL) = (written_off_at IS NULL)
        AND (written_off_amount IS NULL OR written_off_amount > 0)),
    CONSTRAINT ck_debts_payment_method CHECK (payment_method IS NULL OR payment_method IN
        ('CASH', 'UPI', 'DEBIT_CARD', 'CREDIT_CARD', 'NET_BANKING', 'WALLET', 'CHEQUE', 'OTHER'))
);

CREATE INDEX ix_debts_user_direction ON debts (user_id, direction) WHERE deleted_at IS NULL;
CREATE INDEX ix_debts_person ON debts (person_id);

-- Each repayment is its own record; voiding keeps it for history.
CREATE TABLE debt_repayments (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    debt_id         UUID          NOT NULL REFERENCES debts (id),
    amount          NUMERIC(19,4) NOT NULL,
    repayment_date  DATE          NOT NULL,
    payment_method  VARCHAR(20),
    notes           VARCHAR(500),
    voided_at       TIMESTAMPTZ,
    void_reason     VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_debt_repayments_amount CHECK (amount > 0),
    CONSTRAINT ck_debt_repayments_payment_method CHECK (payment_method IS NULL OR payment_method IN
        ('CASH', 'UPI', 'DEBIT_CARD', 'CREDIT_CARD', 'NET_BANKING', 'WALLET', 'CHEQUE', 'OTHER'))
);

CREATE INDEX ix_debt_repayments_debt ON debt_repayments (debt_id, repayment_date);
