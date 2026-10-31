-- Spending limits. Only EXPENSE transactions count against them; transfers never do.
-- Each period starts fresh (no rollover).

CREATE TABLE budgets (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name              VARCHAR(60)   NOT NULL,
    scope             VARCHAR(10)   NOT NULL,
    category_id       UUID          REFERENCES categories (id),
    period            VARCHAR(10)   NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    start_date        DATE          NOT NULL,  -- MONTHLY/WEEKLY: in effect from; CUSTOM: range start
    end_date          DATE,                    -- CUSTOM: range end (required); others: optional stop
    alert_thresholds  VARCHAR(40)   NOT NULL DEFAULT '75,90,100',  -- percentages, ascending
    active            BOOLEAN       NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_budgets_scope CHECK (scope IN ('OVERALL', 'CATEGORY')),
    CONSTRAINT ck_budgets_scope_category CHECK ((scope = 'CATEGORY') = (category_id IS NOT NULL)),
    CONSTRAINT ck_budgets_period CHECK (period IN ('MONTHLY', 'WEEKLY', 'CUSTOM')),
    CONSTRAINT ck_budgets_custom_end CHECK (period <> 'CUSTOM' OR end_date IS NOT NULL),
    CONSTRAINT ck_budgets_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_budgets_amount CHECK (amount > 0),
    CONSTRAINT ck_budgets_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_budgets_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_budgets_thresholds CHECK (alert_thresholds ~ '^[0-9]{1,3}(,[0-9]{1,3}){0,4}$')
);

-- One active recurring budget per scope, category, period and currency.
CREATE UNIQUE INDEX uk_budgets_active_recurring ON budgets (user_id, scope, category_id, period, currency)
    NULLS NOT DISTINCT WHERE active AND period <> 'CUSTOM';
CREATE INDEX ix_budgets_user ON budgets (user_id);
