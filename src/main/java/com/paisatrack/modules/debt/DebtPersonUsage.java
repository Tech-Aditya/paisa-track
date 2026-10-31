package com.paisatrack.modules.debt;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paisatrack.modules.people.PersonUsageProvider;

/** Blocks deleting a contact who has lending or borrowing records (deleted ones included, for history). */
@Component
class DebtPersonUsage implements PersonUsageProvider {

    private final DebtRepository debts;

    DebtPersonUsage(DebtRepository debts) {
        this.debts = debts;
    }

    @Override
    public boolean isUsed(UUID userId, UUID personId) {
        return debts.existsByUserIdAndPersonId(userId, personId);
    }
}
