package com.paisatrack.modules.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.modules.transaction.TransactionStatus;
import com.paisatrack.modules.transaction.TransactionType;

public record TransactionResponse(
        UUID id,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        LocalDate transactionDate,
        LocalTime transactionTime,
        @Schema(description = "Paid from (expense), received into (income) or transfer source") AccountInfo account,
        @Schema(description = "Transfer destination; null otherwise") AccountInfo toAccount,
        @Schema(description = "Null for transfers") CategoryInfo category,
        String description,
        @Schema(description = "Merchant for expenses, payer for income") String counterparty,
        PaymentMethod paymentMethod,
        String notes,
        @Schema(description = "Income only: the source this receipt settles") SourceInfo incomeSource,
        @Schema(description = "Income only: pay period settled", type = "string", example = "2026-10") YearMonth incomePeriod,
        @Schema(description = "Set when a loan created this entry; change it from the loan") UUID loanId,
        Instant voidedAt,
        String voidReason,
        Instant createdAt,
        Instant updatedAt) {

    public record AccountInfo(UUID id, String name) {
    }

    public record CategoryInfo(UUID id, String name, UUID parentId, String parentName) {
    }

    public record SourceInfo(UUID id, String name) {
    }
}
