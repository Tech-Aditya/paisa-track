package com.paisatrack.modules.income;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paisatrack.modules.category.CategoryUsageProvider;

/** Separate bean (not the service) so CategoryService and IncomeSourceService don't depend on each other. */
@Component
class IncomeCategoryUsage implements CategoryUsageProvider {

    private final IncomeSourceRepository sources;

    IncomeCategoryUsage(IncomeSourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public boolean isUsed(UUID userId, UUID categoryId) {
        return sources.existsByUserIdAndCategoryId(userId, categoryId);
    }
}
