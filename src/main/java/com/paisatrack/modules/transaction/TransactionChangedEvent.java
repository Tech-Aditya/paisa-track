package com.paisatrack.modules.transaction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published inside the database transaction when a user edits, voids or restores a transaction,
 * so modules that link to transactions (recurring occurrences) stay consistent.
 */
public record TransactionChangedEvent(UUID userId, UUID transactionId, Change change, BigDecimal amount) {

    public enum Change { UPDATED, VOIDED, RESTORED }
}
