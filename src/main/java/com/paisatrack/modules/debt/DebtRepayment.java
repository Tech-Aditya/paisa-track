package com.paisatrack.modules.debt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.modules.transaction.PaymentMethod;

/** One repayment of a debt. Never edited; a mistake is voided and kept for history. */
@Entity
@Table(name = "debt_repayments")
public class DebtRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "debt_id", nullable = false, updatable = false)
    private UUID debtId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "repayment_date", nullable = false, updatable = false)
    private LocalDate repaymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20, updatable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 200)
    private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DebtRepayment() {
    }

    DebtRepayment(UUID userId, UUID debtId, BigDecimal amount, LocalDate repaymentDate, PaymentMethod paymentMethod,
                  String notes, Instant createdAt) {
        this.userId = userId;
        this.debtId = debtId;
        this.amount = amount;
        this.repaymentDate = repaymentDate;
        this.paymentMethod = paymentMethod;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    void markVoided(Instant at, String reason) {
        this.voidedAt = at;
        this.voidReason = reason;
    }

    public boolean isVoided() {
        return voidedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDebtId() {
        return debtId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getRepaymentDate() {
        return repaymentDate;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
