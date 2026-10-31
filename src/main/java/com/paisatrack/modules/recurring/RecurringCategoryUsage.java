package com.paisatrack.modules.recurring;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paisatrack.modules.category.CategoryUsageProvider;

/** A category a recurring rule posts to can't be deleted. */
@Component
class RecurringCategoryUsage implements CategoryUsageProvider {

    private final RecurringRuleRepository rules;

    RecurringCategoryUsage(RecurringRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    public boolean isUsed(UUID userId, UUID categoryId) {
        return rules.existsByUserIdAndCategoryId(userId, categoryId);
    }
}
