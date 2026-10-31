-- Formal loans (home, vehicle, personal...) with a stored EMI schedule.
-- EMI payments post to the ledger: interest as an EXPENSE, principal as LOAN_PRINCIPAL
-- (an outflow that is never spending). An optional disbursement is LOAN_DISBURSEMENT
-- (an inflow that is never income). Outstanding principal is derived from payments.

CREATE TABLE loans (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                     VARCHAR(80)   NOT NULL,
    lender                   VARCHAR(100),
    loan_type                VARCHAR(20)   NOT NULL,
    principal                NUMERIC(19,4) NOT NULL,
    currency                 VARCHAR(3)    NOT NULL,
    annual_rate              NUMERIC(7,4)  NOT NULL,          -- percent per year, e.g. 8.5000
    tenure_months            INT           NOT NULL,
    emi_amount               NUMERIC(19,4) NOT NULL,
    start_date               DATE          NOT NULL,          -- disbursement date
    first_emi_date           DATE          NOT NULL,
    disbursement_account_id  UUID          REFERENCES accounts (id),
    disbursement_transaction_id UUID,                         -- ledger entry for the disbursement, if any
    payment_account_id       UUID          REFERENCES accounts (id),   -- default account for EMIs
    interest_category_id     UUID          NOT NULL REFERENCES categories (id),
    notes                    VARCHAR(1000),
    deleted_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_loans_type CHECK (loan_type IN ('HOME', 'VEHICLE', 'PERSONAL', 'EDUCATION', 'GOLD', 'OTHER')),
    CONSTRAINT ck_loans_principal CHECK (principal > 0),
    CONSTRAINT ck_loans_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_loans_rate CHECK (annual_rate >= 0 AND annual_rate <= 100),
    CONSTRAINT ck_loans_tenure CHECK (tenure_months BETWEEN 1 AND 600),
    CONSTRAINT ck_loans_emi CHECK (emi_amount > 0),
    CONSTRAINT ck_loans_first_emi CHECK (first_emi_date >= start_date),
    CONSTRAINT ck_loans_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_loans_user ON loans (user_id) WHERE deleted_at IS NULL;

-- One row per installment. Paid rows are frozen; unpaid rows are regenerated after a prepayment.
CREATE TABLE loan_installments (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID          NOT NULL REFERENCES loans (id) ON DELETE CASCADE,
    installment_number  INT           NOT NULL,
    due_date            DATE          NOT NULL,
    opening_balance     NUMERIC(19,4) NOT NULL,
    emi_amount          NUMERIC(19,4) NOT NULL,
    principal_amount    NUMERIC(19,4) NOT NULL,
    interest_amount     NUMERIC(19,4) NOT NULL,
    closing_balance     NUMERIC(19,4) NOT NULL,
    CONSTRAINT uk_loan_installments_number UNIQUE (loan_id, installment_number),
    CONSTRAINT ck_loan_installments_amounts CHECK (principal_amount >= 0 AND interest_amount >= 0
        AND emi_amount = principal_amount + interest_amount AND closing_balance >= 0)
);

-- EMIs and prepayments actually made. Voided rows are kept for history. EMIs paid before the
-- loan was added have no account and post nothing to the ledger.
CREATE TABLE loan_payments (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    loan_id                   UUID          NOT NULL REFERENCES loans (id),
    kind                      VARCHAR(12)   NOT NULL,
    installment_number        INT,
    payment_date              DATE          NOT NULL,
    principal_amount          NUMERIC(19,4) NOT NULL,
    interest_amount           NUMERIC(19,4) NOT NULL DEFAULT 0,
    account_id                UUID          REFERENCES accounts (id),     -- null: paid before tracking, not in the ledger
    principal_transaction_id  UUID,
    interest_transaction_id   UUID,
    voided_at                 TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_loan_payments_kind CHECK (kind IN ('EMI', 'PREPAYMENT')),
    CONSTRAINT ck_loan_payments_shape CHECK ((kind = 'EMI') = (installment_number IS NOT NULL)
        AND (kind = 'EMI' OR interest_amount = 0)),
    CONSTRAINT ck_loan_payments_amounts CHECK (principal_amount >= 0 AND interest_amount >= 0
        AND principal_amount + interest_amount > 0)
);

CREATE INDEX ix_loan_payments_loan ON loan_payments (loan_id, created_at);
CREATE UNIQUE INDEX uk_loan_payments_active_emi ON loan_payments (loan_id, installment_number)
    WHERE kind = 'EMI' AND voided_at IS NULL;

-- Ledger: loan-generated transactions point at their loan. LOAN_DISBURSEMENT needs a wider type column.
ALTER TABLE transactions ALTER COLUMN type TYPE VARCHAR(20);
ALTER TABLE transactions ADD COLUMN loan_id UUID REFERENCES loans (id);
CREATE INDEX ix_transactions_loan ON transactions (loan_id) WHERE loan_id IS NOT NULL;

ALTER TABLE transactions DROP CONSTRAINT ck_transactions_type;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_type
    CHECK (type IN ('EXPENSE', 'INCOME', 'TRANSFER', 'LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL'));

ALTER TABLE transactions DROP CONSTRAINT ck_transactions_shape;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_shape CHECK (
    (type = 'TRANSFER' AND to_account_id IS NOT NULL AND to_account_id <> account_id AND category_id IS NULL)
    OR (type IN ('EXPENSE', 'INCOME') AND to_account_id IS NULL AND category_id IS NOT NULL)
    OR (type IN ('LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL') AND to_account_id IS NULL AND category_id IS NULL
        AND loan_id IS NOT NULL));
