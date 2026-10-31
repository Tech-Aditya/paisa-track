package com.paisatrack.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.common.persistence.AuditableEntity;

/**
 * A recorded money movement. {@code transactionDate} is the user's calendar date, kept apart from
 * the created/updated instants so back-dated entries work.
 */
@Entity
@Table(name = "transactions")
public class Transaction extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TransactionStatus status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "to_account_id")
    private UUID toAccountId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "transaction_time")
    private LocalTime transactionTime;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "counterparty", length = 100)
    private String counterparty;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 200)
    private String voidReason;

    protected Transaction() {
    }

    Transaction(UUID userId, TransactionType type, TransactionDetails details) {
        this.userId = userId;
        this.type = type;
        this.status = TransactionStatus.ACTIVE;
        apply(details);
    }

    void apply(TransactionDetails d) {
        this.amount = d.amount();
        this.currency = d.currency();
        this.accountId = d.accountId();
        this.toAccountId = d.toAccountId();
        this.categoryId = d.categoryId();
        this.transactionDate = d.transactionDate();
        this.transactionTime = d.transactionTime();
        this.description = d.description();
        this.counterparty = d.counterparty();
        this.paymentMethod = d.paymentMethod();
        this.notes = d.notes();
    }

    void markVoided(Instant at, String reason) {
        this.status = TransactionStatus.VOIDED;
        this.voidedAt = at;
        this.voidReason = reason;
    }

    void restore() {
        this.status = TransactionStatus.ACTIVE;
        this.voidedAt = null;
        this.voidReason = null;
    }

    boolean isVoided() {
        return status == TransactionStatus.VOIDED;
    }

    /** Audited fields, as JSON-friendly values. */
    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("amount", amount.stripTrailingZeros().toPlainString());
        s.put("currency", currency);
        s.put("accountId", accountId);
        s.put("toAccountId", toAccountId);
        s.put("categoryId", categoryId);
        s.put("transactionDate", transactionDate.toString());
        s.put("transactionTime", transactionTime == null ? null : transactionTime.toString());
        s.put("description", description);
        s.put("counterparty", counterparty);
        s.put("paymentMethod", paymentMethod);
        s.put("notes", notes);
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public LocalTime getTransactionTime() {
        return transactionTime;
    }

    public String getDescription() {
        return description;
    }

    public String getCounterparty() {
        return counterparty;
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
}
