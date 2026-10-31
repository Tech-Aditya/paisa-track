package com.paisatrack.modules.account;

public enum AccountType {
    CASH(AccountNature.ASSET),
    SAVINGS(AccountNature.ASSET),
    CURRENT(AccountNature.ASSET),
    CREDIT_CARD(AccountNature.LIABILITY),
    WALLET(AccountNature.ASSET),
    /** Mutual funds, stocks, PPF, FDs: transfers in are investments, never spending. */
    INVESTMENT(AccountNature.ASSET),
    OTHER(AccountNature.ASSET);

    private final AccountNature nature;

    AccountType(AccountNature nature) {
        this.nature = nature;
    }

    public AccountNature nature() {
        return nature;
    }
}
