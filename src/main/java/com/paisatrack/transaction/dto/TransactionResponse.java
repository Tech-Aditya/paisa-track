package com.paisatrack.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.transaction.PaymentMethod;
import com.paisatrack.transaction.TransactionStatus;
import com.paisatrack.transaction.TransactionType;

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
        Instant voidedAt,
        String voidReason,
        Instant createdAt,
        Instant updatedAt) {

    public record AccountInfo(UUID id, String name) {
    }

    public record CategoryInfo(UUID id, String name, UUID parentId, String parentName) {
    }
}
