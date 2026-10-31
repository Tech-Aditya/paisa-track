package com.paisatrack.modules.income;

public enum IncomeSourceType {
    SALARY("INC_SALARY"),
    BONUS("INC_BONUS"),
    FREELANCE("INC_FREELANCE"),
    BUSINESS("INC_BUSINESS"),
    RENTAL("INC_RENTAL"),
    INTEREST("INC_INTEREST"),
    GIFT("INC_GIFTS"),
    OTHER("INC_OTHER");

    private final String defaultCategoryCode;

    IncomeSourceType(String defaultCategoryCode) {
        this.defaultCategoryCode = defaultCategoryCode;
    }

    /** System code of the seeded income category used when none is given. */
    String defaultCategoryCode() {
        return defaultCategoryCode;
    }
}
