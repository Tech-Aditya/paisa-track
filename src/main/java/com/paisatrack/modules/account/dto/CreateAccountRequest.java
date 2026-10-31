package com.paisatrack.modules.account.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.account.AccountType;
import com.paisatrack.platform.validation.CurrencyCode;

public record CreateAccountRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull AccountType type,
        @Schema(description = "Defaults to your preferred currency; can't change later") @CurrencyCode String currency,
        @Schema(description = "Assets: money held now. Credit cards: amount owed now. Defaults to 0") BigDecimal openingBalance,
        @Schema(description = "Credit cards only") BigDecimal creditLimit,
        @Size(max = 100) String institution,
        @Schema(description = "Last 4 digits only, never the full number") @Pattern(regexp = "\\d{4}") String accountLast4,
        @Size(max = 500) String notes) {
}
