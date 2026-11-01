package com.paisatrack.modules.imports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.transaction.TransactionType;

/** What the file would add. Nothing is saved until /imports/commit. */
public record ImportPreviewResponse(
        String fileName,
        UUID accountId,
        String accountName,
        String currency,
        List<String> headers,
        ImportMapping mapping,
        @Schema(description = "Date styles the user can pick from") List<String> dateFormats,
        int rowCount,
        int selectedCount,
        int duplicateCount,
        int errorCount,
        List<Row> rows) {

    public record Row(
            @Schema(description = "Line number in the file (1-based)") int line,
            LocalDate date,
            TransactionType type,
            BigDecimal amount,
            String description,
            UUID categoryId,
            String categoryName,
            @Schema(description = "HISTORY or KEYWORD when a category was suggested") String categoryReason,
            @Schema(description = "Why this looks already recorded; null when it doesn't") String duplicateReason,
            UUID duplicateOfTransactionId,
            @Schema(description = "Ticked by default: false for duplicates and rows with errors") boolean selected,
            String error) {
    }
}
