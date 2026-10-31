package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.loan.LoanPayment;

public record LoanPaymentResponse(
        UUID id,
        LoanPayment.Kind kind,
        Integer installmentNumber,
        LocalDate paymentDate,
        BigDecimal principalAmount,
        BigDecimal interestAmount,
        BigDecimal totalAmount,
        LoanResponse.Ref account,
        @Schema(description = "false for EMIs paid before tracking: nothing was posted to your accounts") boolean inLedger,
        boolean voided,
        Instant voidedAt,
        Instant createdAt) {
}
