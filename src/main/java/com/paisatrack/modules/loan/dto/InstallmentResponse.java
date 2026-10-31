package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentResponse(
        int number,
        LocalDate dueDate,
        BigDecimal emiAmount,
        BigDecimal principalAmount,
        BigDecimal interestAmount,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        Status status,
        LocalDate paidOn,
        UUID paymentId) {

    public enum Status {
        PAID,
        /** Due date passed and not paid. */
        MISSED,
        /** The next one to pay, not yet late. */
        DUE,
        UPCOMING
    }
}
