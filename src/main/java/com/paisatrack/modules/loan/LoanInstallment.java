package com.paisatrack.modules.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One scheduled EMI. Rows already paid are never changed; unpaid rows are regenerated after a prepayment. */
@Entity
@Table(name = "loan_installments")
public class LoanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "installment_number", nullable = false, updatable = false)
    private int number;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal openingBalance;

    @Column(name = "emi_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal emiAmount;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal interestAmount;

    @Column(name = "closing_balance", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal closingBalance;

    protected LoanInstallment() {
    }

    LoanInstallment(UUID loanId, Amortization.Row row) {
        this.loanId = loanId;
        this.number = row.number();
        this.dueDate = row.dueDate();
        this.openingBalance = row.opening();
        this.emiAmount = row.emi();
        this.principalAmount = row.principal();
        this.interestAmount = row.interest();
        this.closingBalance = row.closing();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLoanId() {
        return loanId;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public BigDecimal getEmiAmount() {
        return emiAmount;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public BigDecimal getInterestAmount() {
        return interestAmount;
    }

    public BigDecimal getClosingBalance() {
        return closingBalance;
    }
}
