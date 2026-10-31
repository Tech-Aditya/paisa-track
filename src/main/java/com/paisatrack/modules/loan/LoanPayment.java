package com.paisatrack.modules.loan;

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

/** An EMI or prepayment actually made. A null account means it was paid before tracking (no ledger entries). */
@Entity
@Table(name = "loan_payments")
public class LoanPayment {

    public enum Kind { EMI, PREPAYMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12, updatable = false)
    private Kind kind;

    @Column(name = "installment_number", updatable = false)
    private Integer installmentNumber;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal interestAmount;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "principal_transaction_id", updatable = false)
    private UUID principalTransactionId;

    @Column(name = "interest_transaction_id", updatable = false)
    private UUID interestTransactionId;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoanPayment() {
    }

    LoanPayment(UUID userId, UUID loanId, Kind kind, Integer installmentNumber, LocalDate paymentDate,
                BigDecimal principalAmount, BigDecimal interestAmount, UUID accountId,
                UUID principalTransactionId, UUID interestTransactionId, Instant createdAt) {
        this.userId = userId;
        this.loanId = loanId;
        this.kind = kind;
        this.installmentNumber = installmentNumber;
        this.paymentDate = paymentDate;
        this.principalAmount = principalAmount;
        this.interestAmount = interestAmount;
        this.accountId = accountId;
        this.principalTransactionId = principalTransactionId;
        this.interestTransactionId = interestTransactionId;
        this.createdAt = createdAt;
    }

    void markVoided(Instant at) {
        this.voidedAt = at;
    }

    public boolean isVoided() {
        return voidedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLoanId() {
        return loanId;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getInstallmentNumber() {
        return installmentNumber;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public BigDecimal getInterestAmount() {
        return interestAmount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getPrincipalTransactionId() {
        return principalTransactionId;
    }

    public UUID getInterestTransactionId() {
        return interestTransactionId;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
