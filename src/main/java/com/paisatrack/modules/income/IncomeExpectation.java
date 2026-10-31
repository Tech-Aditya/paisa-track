package com.paisatrack.modules.income;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Expected monthly amount from {@code effectiveMonth} until the next expectation. Null amount = none expected. */
@Entity
@Table(name = "income_expectations")
class IncomeExpectation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "income_source_id", nullable = false, updatable = false)
    private UUID incomeSourceId;

    @Column(name = "effective_month", nullable = false, updatable = false)
    private LocalDate effectiveMonth;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "expected_day")
    private Short expectedDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IncomeExpectation() {
    }

    IncomeExpectation(UUID incomeSourceId, LocalDate effectiveMonth, BigDecimal amount, Integer expectedDay, Instant now) {
        this.incomeSourceId = incomeSourceId;
        this.effectiveMonth = effectiveMonth;
        this.createdAt = now;
        set(amount, expectedDay);
    }

    void set(BigDecimal amount, Integer expectedDay) {
        this.amount = amount;
        this.expectedDay = amount == null ? null : expectedDay.shortValue();
    }

    UUID getIncomeSourceId() {
        return incomeSourceId;
    }

    LocalDate getEffectiveMonth() {
        return effectiveMonth;
    }

    BigDecimal getAmount() {
        return amount;
    }

    Integer getExpectedDay() {
        return expectedDay == null ? null : expectedDay.intValue();
    }
}
