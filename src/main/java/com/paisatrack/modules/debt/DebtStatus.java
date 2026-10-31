package com.paisatrack.modules.debt;

/** Derived on read, never stored. Checked in this order. */
public enum DebtStatus {
    /** Closed with an unpaid balance that won't be collected or repaid. */
    WRITTEN_OFF,
    /** Nothing left outstanding. */
    SETTLED,
    /** Past its due date with money still outstanding. */
    OVERDUE,
    PARTIALLY_REPAID,
    OPEN
}
