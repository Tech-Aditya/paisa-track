package com.paisatrack.modules.category;

import java.util.UUID;

/**
 * Something that references categories (transactions, budgets, income sources). A category any
 * provider reports as used can't be deleted. Implemented by the referencing modules.
 */
public interface CategoryUsageProvider {

    boolean isUsed(UUID userId, UUID categoryId);
}
