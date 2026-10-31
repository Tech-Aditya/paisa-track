package com.paisatrack.modules.loan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import com.paisatrack.platform.persistence.AuditableEntity;

/** A formal loan. Terms are fixed once payments exist; the schedule lives in {@link LoanInstallment}. */
@Entity
@Table(name = "loans")
public class Loan extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "lender", length = 100)
    private String lender;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_type", nullable = false, length = 20)
    private LoanType type;

    @Column(name = "principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "annual_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "emi_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal emiAmount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "first_emi_date", nullable = false)
    private LocalDate firstEmiDate;

    @Column(name = "disbursement_account_id")
    private UUID disbursementAccountId;

    @Column(name = "disbursement_transaction_id")
    private UUID disbursementTransactionId;

    @Column(name = "payment_account_id")
    private UUID paymentAccountId;

    @Column(name = "interest_category_id", nullable = false)
    private UUID interestCategoryId;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Loan() {
    }

    Loan(UUID userId, String name, LoanType type, String currency) {
        this.userId = userId;
        this.name = name.strip();
        this.type = type;
        this.currency = currency;
    }

    void setTerms(BigDecimal principal, BigDecimal annualRate, int tenureMonths, BigDecimal emiAmount,
                  LocalDate startDate, LocalDate firstEmiDate) {
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.emiAmount = emiAmount;
        this.startDate = startDate;
        this.firstEmiDate = firstEmiDate;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("lender", lender);
        s.put("type", type);
        s.put("principal", principal.stripTrailingZeros().toPlainString());
        s.put("annualRate", annualRate.stripTrailingZeros().toPlainString());
        s.put("tenureMonths", tenureMonths);
        s.put("emiAmount", emiAmount.stripTrailingZeros().toPlainString());
        s.put("startDate", startDate.toString());
        s.put("firstEmiDate", firstEmiDate.toString());
        s.put("disbursementAccountId", disbursementAccountId);
        s.put("paymentAccountId", paymentAccountId);
        s.put("interestCategoryId", interestCategoryId);
        s.put("notes", notes);
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getLender() {
        return lender;
    }

    public LoanType getType() {
        return type;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public int getTenureMonths() {
        return tenureMonths;
    }

    public BigDecimal getEmiAmount() {
        return emiAmount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getFirstEmiDate() {
        return firstEmiDate;
    }

    public UUID getDisbursementAccountId() {
        return disbursementAccountId;
    }

    public UUID getDisbursementTransactionId() {
        return disbursementTransactionId;
    }

    public UUID getPaymentAccountId() {
        return paymentAccountId;
    }

    public UUID getInterestCategoryId() {
        return interestCategoryId;
    }

    public String getNotes() {
        return notes;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setLender(String lender) {
        this.lender = lender;
    }

    void setType(LoanType type) {
        this.type = type;
    }

    void setDisbursement(UUID accountId, UUID transactionId) {
        this.disbursementAccountId = accountId;
        this.disbursementTransactionId = transactionId;
    }

    void setPaymentAccountId(UUID paymentAccountId) {
        this.paymentAccountId = paymentAccountId;
    }

    void setInterestCategoryId(UUID interestCategoryId) {
        this.interestCategoryId = interestCategoryId;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    void markDeleted(Instant at) {
        this.deletedAt = at;
    }
}
