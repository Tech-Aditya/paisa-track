package com.paisatrack.modules.transaction;

/** TRANSFER moves money between the user's own accounts and is never income or expense. */
public enum TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER
}
