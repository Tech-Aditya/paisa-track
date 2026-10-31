package com.paisatrack.modules.recurring;

import com.paisatrack.modules.category.CategoryType;

/** The transaction a confirmed occurrence records. */
public enum RecurringType {
    EXPENSE,
    INCOME,
    TRANSFER;

    CategoryType categoryType() {
        return switch (this) {
            case EXPENSE -> CategoryType.EXPENSE;
            case INCOME -> CategoryType.INCOME;
            case TRANSFER -> null;
        };
    }
}
