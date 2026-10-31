package com.paisatrack.modules.transaction;

/** VOIDED transactions are kept for history but excluded from balances and totals. */
public enum TransactionStatus {
    ACTIVE,
    VOIDED
}
