package com.paisatrack.modules.imports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.transaction.TransactionType;

/** The rows the user ticked in the preview, possibly with categories changed. All are saved or none. */
public record ImportCommitRequest(
        @NotNull UUID accountId,
        @Size(max = 200) String fileName,
        @NotEmpty @Size(max = 2000) List<@Valid @NotNull Item> items) {

    public record Item(
            @NotNull LocalDate date,
            @NotNull @Schema(description = "EXPENSE or INCOME") TransactionType type,
            @NotNull BigDecimal amount,
            @NotNull UUID categoryId,
            @Size(max = 200) String description) {
    }
}
