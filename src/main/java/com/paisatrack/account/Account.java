package com.paisatrack.account;

import java.math.BigDecimal;
import java.time.Instant;
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

@Entity
@Table(name = "accounts")
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private AccountType type;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "credit_limit", precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "institution", length = 100)
    private String institution;

    @Column(name = "account_last4", length = 4)
    private String accountLast4;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAccount;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected Account() {
    }

    Account(UUID userId, String name, AccountType type, String currency, BigDecimal openingBalance) {
        this.userId = userId;
        this.name = name.strip();
        this.type = type;
        this.currency = currency;
        this.openingBalance = openingBalance;
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

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public String getInstitution() {
        return institution;
    }

    public String getAccountLast4() {
        return accountLast4;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isDefaultAccount() {
        return defaultAccount;
    }

    public boolean isActive() {
        return active;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    void setInstitution(String institution) {
        this.institution = institution;
    }

    void setAccountLast4(String accountLast4) {
        this.accountLast4 = accountLast4;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    void markDefault() {
        this.defaultAccount = true;
    }

    void deactivate(Instant at) {
        this.active = false;
        this.deactivatedAt = at;
    }

    void activate() {
        this.active = true;
        this.deactivatedAt = null;
    }

    /** Audited fields. */
    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("openingBalance", openingBalance == null ? null : openingBalance.stripTrailingZeros().toPlainString());
        s.put("creditLimit", creditLimit == null ? null : creditLimit.stripTrailingZeros().toPlainString());
        s.put("institution", institution);
        s.put("accountLast4", accountLast4);
        s.put("notes", notes);
        return s;
    }
}
