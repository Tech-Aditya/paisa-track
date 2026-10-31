package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Extra principal. The EMI stays the same and the remaining schedule gets shorter. */
public record PrepaymentRequest(
        @NotNull @Schema(example = "100000") BigDecimal amount,
        LocalDate paymentDate,
        UUID accountId) {
}
