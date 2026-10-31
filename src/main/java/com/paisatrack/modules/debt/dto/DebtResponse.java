package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.debt.DebtDirection;
import com.paisatrack.modules.debt.DebtStatus;
import com.paisatrack.modules.transaction.PaymentMethod;

public record DebtResponse(
        UUID id,
        DebtDirection direction,
        PersonInfo person,
        @Schema(description = "Original amount") BigDecimal principal,
        String currency,
        @Schema(description = "Sum of active repayments") BigDecimal repaid,
        BigDecimal writtenOff,
        @Schema(description = "principal - repaid - writtenOff") BigDecimal outstanding,
        DebtStatus status,
        boolean overdue,
        @Schema(description = "Days past the due date while still outstanding") long daysOverdue,
        LocalDate startDate,
        LocalDate dueDate,
        LocalDate remindOn,
        String description,
        String notes,
        PaymentMethod paymentMethod,
        long repaymentCount,
        LocalDate lastRepaymentDate,
        Instant writtenOffAt,
        String writeOffReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Included when fetching a single record") List<RepaymentResponse> repayments,
        Instant createdAt,
        Instant updatedAt) {

    public record PersonInfo(UUID id, String name) {
    }
}
