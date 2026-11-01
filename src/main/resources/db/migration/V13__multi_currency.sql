-- Multi-currency. Amounts are never rewritten: every record keeps its own currency, and
-- conversions are computed when reporting, from stored exchange rates.

-- One row per pair and day. Manual rates belong to a user; provider rates (user_id null) are
-- shared and keyed by their source. rate = how many 'quote' one 'base' buys.
CREATE TABLE exchange_rates (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID          REFERENCES users (id) ON DELETE CASCADE,
    base        VARCHAR(3)    NOT NULL,
    quote       VARCHAR(3)    NOT NULL,
    rate        NUMERIC(24,10) NOT NULL,
    rate_date   DATE          NOT NULL,
    source      VARCHAR(30)   NOT NULL,             -- MANUAL or the provider's name
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_exchange_rates_pair CHECK (base ~ '^[A-Z]{3}$' AND quote ~ '^[A-Z]{3}$' AND base <> quote),
    CONSTRAINT ck_exchange_rates_rate CHECK (rate > 0),
    CONSTRAINT ck_exchange_rates_owner CHECK ((user_id IS NULL) = (source <> 'MANUAL'))
);

CREATE UNIQUE INDEX uk_exchange_rates_manual ON exchange_rates (user_id, base, quote, rate_date) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uk_exchange_rates_provider ON exchange_rates (source, base, quote, rate_date) WHERE user_id IS NULL;
CREATE INDEX ix_exchange_rates_pair ON exchange_rates (base, quote, rate_date);

-- Transfers between accounts in different currencies record both sides: 'amount' leaves the
-- source account in 'currency', 'to_amount' arrives in 'to_currency'. The implied rate is derived.
ALTER TABLE transactions ADD COLUMN to_amount NUMERIC(19,4);
ALTER TABLE transactions ADD COLUMN to_currency VARCHAR(3);
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_to_amount CHECK (
    (to_amount IS NULL) = (to_currency IS NULL)
    AND (to_amount IS NULL OR (type = 'TRANSFER' AND to_amount > 0 AND to_currency <> currency)));
