-- Investment accounts (mutual funds, stocks, PPF, FDs). Transfers into one are investments and
-- transfers out of one are withdrawals; neither is ever income or spending.
ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('CASH', 'SAVINGS', 'CURRENT', 'CREDIT_CARD', 'WALLET', 'INVESTMENT', 'OTHER'));
