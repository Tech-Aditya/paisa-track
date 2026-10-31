package com.paisatrack.category;

import java.util.UUID;

/** Whether any transaction (voided included) uses a category. Implemented by the transaction module. */
public interface CategoryUsageProvider {

    boolean isUsed(UUID userId, UUID categoryId);
}
