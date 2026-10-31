package com.paisatrack.modules.account.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.NullOrNotBlank;

/** Partial update: omitted fields stay as they are; an empty string clears a text field. Type and currency are fixed. */
public record UpdateAccountRequest(
        @NullOrNotBlank @Size(max = 60) String name,
        @Schema(description = "Changing it shifts the current balance by the same amount; audited") BigDecimal openingBalance,
        BigDecimal creditLimit,
        @Size(max = 100) String institution,
        @Pattern(regexp = "(\\d{4})?") String accountLast4,
        @Size(max = 500) String notes) {
}
