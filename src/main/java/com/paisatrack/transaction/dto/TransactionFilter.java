package com.paisatrack.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.paisatrack.transaction.PaymentMethod;
import com.paisatrack.transaction.TransactionType;

/** Query parameters for transaction lists. All optional; dates are inclusive. */
public record TransactionFilter(
        TransactionType type,
        LocalDate from,
        LocalDate to,
        @Schema(description = "Matches either side of a transfer") UUID accountId,
        @Schema(description = "A top-level category also matches its subcategories") UUID categoryId,
        PaymentMethod paymentMethod,
        @Schema(description = "Searches description and merchant/payer") @Size(max = 100) String q,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Schema(description = "Include voided transactions (default false)") Boolean includeVoided,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Schema(description = "DATE (default), AMOUNT or CREATED_AT") SortField sortBy,
        @Schema(description = "ASC or DESC (default)") SortDirection direction) {

    public enum SortField { DATE, AMOUNT, CREATED_AT }

    public enum SortDirection { ASC, DESC }

    public TransactionFilter withType(TransactionType forcedType) {
        return new TransactionFilter(forcedType, from, to, accountId, categoryId, paymentMethod, q,
                minAmount, maxAmount, includeVoided, page, size, sortBy, direction);
    }
}
