package com.paisatrack.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.common.persistence.AuditableEntity;

@Entity
@Table(name = "budgets")
public class Budget extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10, updatable = false)
    private BudgetScope scope;

    @Column(name = "category_id", updatable = false)
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 10, updatable = false)
    private BudgetPeriod period;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Ascending percentages, stored as "75,90,100". */
    @Column(name = "alert_thresholds", nullable = false, length = 40)
    private String alertThresholds;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Budget() {
    }

    Budget(UUID userId, String name, BudgetScope scope, UUID categoryId, BudgetPeriod period, BigDecimal amount,
           String currency, LocalDate startDate, LocalDate endDate, List<Integer> thresholds) {
        this.userId = userId;
        this.name = name.strip();
        this.scope = scope;
        this.categoryId = categoryId;
        this.period = period;
        this.amount = amount;
        this.currency = currency;
        this.startDate = startDate;
        this.endDate = endDate;
        setAlertThresholds(thresholds);
        this.active = true;
    }

    /** Whether the budget applies on {@code date}. */
    boolean inEffectOn(LocalDate date) {
        return !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate));
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

    public BudgetScope getScope() {
        return scope;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public BudgetPeriod getPeriod() {
        return period;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public List<Integer> getAlertThresholds() {
        return Arrays.stream(alertThresholds.split(",")).map(Integer::valueOf).toList();
    }

    public boolean isActive() {
        return active;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    void setAlertThresholds(List<Integer> thresholds) {
        this.alertThresholds = thresholds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    void setActive(boolean active) {
        this.active = active;
    }
}
