package com.paisatrack.modules.income;

import java.util.UUID;

/** Read-only income source view for other modules. */
public record IncomeSourceRef(UUID id, String name, String currency, UUID categoryId, UUID accountId, boolean active) {

    static IncomeSourceRef of(IncomeSource source) {
        return new IncomeSourceRef(source.getId(), source.getName(), source.getCurrency(), source.getCategoryId(),
                source.getAccountId(), source.isActive());
    }
}
