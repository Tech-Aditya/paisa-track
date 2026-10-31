package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.paisatrack.modules.transaction.PaymentMethod;

public record RepaymentResponse(
        UUID id,
        UUID debtId,
        BigDecimal amount,
        String currency,
        LocalDate repaymentDate,
        PaymentMethod paymentMethod,
        String notes,
        boolean voided,
        Instant voidedAt,
        String voidReason,
        Instant createdAt) {
}
