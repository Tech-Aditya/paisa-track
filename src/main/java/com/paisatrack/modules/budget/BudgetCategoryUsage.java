package com.paisatrack.modules.budget;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paisatrack.modules.category.CategoryUsageProvider;

/** Blocks deleting a category that a budget points at. */
@Component
class BudgetCategoryUsage implements CategoryUsageProvider {

    private final BudgetRepository budgets;

    BudgetCategoryUsage(BudgetRepository budgets) {
        this.budgets = budgets;
    }

    @Override
    public boolean isUsed(UUID userId, UUID categoryId) {
        return budgets.existsByUserIdAndCategoryId(userId, categoryId);
    }
}
