package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.loan.LoanType;

public record LoanResponse(
        UUID id,
        String name,
        String lender,
        LoanType type,
        String currency,
        BigDecimal principal,
        BigDecimal annualRate,
        int tenureMonths,
        BigDecimal emiAmount,
        LocalDate startDate,
        LocalDate firstEmiDate,
        Ref disbursementAccount,
        Ref paymentAccount,
        Ref interestCategory,
        Status status,
        @Schema(description = "Principal still owed; counted as a liability in net worth") BigDecimal outstandingPrincipal,
        BigDecimal principalPaid,
        BigDecimal interestPaid,
        int installmentsPaid,
        int installmentsRemaining,
        int missedCount,
        InstallmentResponse nextInstallment,
        @Schema(description = "Interest paid so far plus interest still scheduled") BigDecimal projectedTotalInterest,
        LocalDate projectedEndDate,
        String notes,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LoanPaymentResponse> payments,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { ACTIVE, CLOSED }

    public record Ref(UUID id, String name) {
    }
}
