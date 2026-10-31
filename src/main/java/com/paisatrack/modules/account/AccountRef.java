package com.paisatrack.modules.account;

import java.util.UUID;

/** Read-only account view for other modules. */
public record AccountRef(UUID id, String name, AccountType type, String currency, boolean active) {

    static AccountRef of(Account account) {
        return new AccountRef(account.getId(), account.getName(), account.getType(), account.getCurrency(), account.isActive());
    }
}
