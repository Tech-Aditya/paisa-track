package com.paisatrack.modules.recurring;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One due date of a rule. EXPECTED is only a plan; CONFIRMED points at the real transaction.
 * Rows are inserted by SQL (insert ... on conflict do nothing) so generation can't duplicate them.
 */
@Entity
@Table(name = "recurring_occurrences")
public class RecurringOccurrence {

    public enum Status { EXPECTED, CONFIRMED, SKIPPED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "actual_amount", precision = 19, scale = 4)
    private BigDecimal actualAmount;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RecurringOccurrence() {
    }

    void confirm(UUID transactionId, BigDecimal actualAmount, Instant at, String note) {
        this.status = Status.CONFIRMED;
        this.transactionId = transactionId;
        this.actualAmount = actualAmount;
        this.resolvedAt = at;
        this.note = note;
    }

    void skip(Instant at, String note) {
        this.status = Status.SKIPPED;
        this.transactionId = null;
        this.actualAmount = null;
        this.resolvedAt = at;
        this.note = note;
    }

    /** Back to expected. The voided transaction stays linked so restoring it confirms this again. */
    void reopen(boolean keepTransactionLink) {
        this.status = Status.EXPECTED;
        if (!keepTransactionLink) {
            this.transactionId = null;
        }
        this.actualAmount = null;
        this.resolvedAt = null;
        this.note = null;
    }

    /** The linked transaction was edited or restored. */
    void syncConfirmed(BigDecimal amount, Instant at) {
        if (status != Status.CONFIRMED) {
            this.status = Status.CONFIRMED;
            this.resolvedAt = at;
        }
        this.actualAmount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public BigDecimal getActualAmount() {
        return actualAmount;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getNote() {
        return note;
    }
}
