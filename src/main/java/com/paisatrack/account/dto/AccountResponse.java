package com.paisatrack.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.account.AccountNature;
import com.paisatrack.account.AccountType;

public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        AccountNature nature,
        String currency,
        BigDecimal openingBalance,
        @Schema(description = "ASSET: money held. LIABILITY: amount owed. Derived from transactions")
        BigDecimal currentBalance,
        BigDecimal creditLimit,
        @Schema(description = "Credit limit minus amount owed; credit cards with a limit only")
        BigDecimal availableCredit,
        String institution,
        String accountLast4,
        String notes,
        boolean defaultAccount,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
