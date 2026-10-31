package com.paisatrack.modules.transaction;

import com.paisatrack.modules.account.AccountType;

public enum PaymentMethod {
    CASH,
    UPI,
    DEBIT_CARD,
    CREDIT_CARD,
    NET_BANKING,
    WALLET,
    CHEQUE,
    OTHER;

    /** Sensible default when the client sends none; null when the account type doesn't imply one. */
    static PaymentMethod defaultFor(AccountType accountType) {
        return switch (accountType) {
            case CASH -> CASH;
            case CREDIT_CARD -> CREDIT_CARD;
            case WALLET -> WALLET;
            default -> null;
        };
    }
}
