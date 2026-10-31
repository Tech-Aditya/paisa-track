package com.paisatrack.modules.loan.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Pays the next unpaid installment, split into principal and interest as scheduled. */
public record EmiPaymentRequest(
        @Schema(description = "Must be the next unpaid installment; defaults to it") Integer installmentNumber,
        @Schema(description = "Defaults to today") LocalDate paymentDate,
        @Schema(description = "Defaults to the loan's payment account, else your default account") UUID accountId) {
}
