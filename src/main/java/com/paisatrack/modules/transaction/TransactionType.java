package com.paisatrack.modules.transaction;

/**
 * TRANSFER moves money between the user's own accounts. LOAN_DISBURSEMENT (money in) and
 * LOAN_PRINCIPAL (money out) are loan movements. None of these are ever income or expense.
 */
public enum TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    LOAN_DISBURSEMENT,
    LOAN_PRINCIPAL;

    /** Raises the balance of the account it is posted to. */
    public boolean isInflow() {
        return this == INCOME || this == LOAN_DISBURSEMENT;
    }
}
