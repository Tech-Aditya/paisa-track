package com.paisatrack.modules.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Validated, resolved values for a transaction, ready to store. */
record TransactionDetails(
        BigDecimal amount,
        String currency,
        UUID accountId,
        UUID toAccountId,
        UUID categoryId,
        LocalDate transactionDate,
        LocalTime transactionTime,
        String description,
        String counterparty,
        PaymentMethod paymentMethod,
        String notes,
        UUID incomeSourceId,
        LocalDate incomePeriod,
        UUID loanId,
        BigDecimal toAmount,
        String toCurrency) {
}
