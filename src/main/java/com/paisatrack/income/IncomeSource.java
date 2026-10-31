package com.paisatrack.income;

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

import com.paisatrack.common.persistence.AuditableEntity;

/** Where income comes from. Expected amounts live in {@link IncomeExpectation}, by month. */
@Entity
@Table(name = "income_sources")
public class IncomeSource extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private IncomeSourceType type;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "account_id")
    private UUID accountId;

    /** First day of the first month with expectations. */
    @Column(name = "start_month", nullable = false)
    private LocalDate startMonth;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected IncomeSource() {
    }

    IncomeSource(UUID userId, String name, IncomeSourceType type, String currency, UUID categoryId, UUID accountId,
                 LocalDate startMonth, String notes) {
        this.userId = userId;
        this.name = name.strip();
        this.type = type;
        this.currency = currency;
        this.categoryId = categoryId;
        this.accountId = accountId;
        this.startMonth = startMonth;
        this.notes = notes;
        this.active = true;
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

    public IncomeSourceType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public LocalDate getStartMonth() {
        return startMonth;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setType(IncomeSourceType type) {
        this.type = type;
    }

    void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    void setActive(boolean active) {
        this.active = active;
    }
}
