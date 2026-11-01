package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How many {@code quote} one {@code base} buys on a day. Manual rates have an owner; provider rates are shared. */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    static final String MANUAL = "MANUAL";

    @Id
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "base", nullable = false, length = 3, updatable = false)
    private String base;

    @Column(name = "quote", nullable = false, length = 3, updatable = false)
    private String quote;

    @Column(name = "rate", nullable = false, precision = 24, scale = 10)
    private BigDecimal rate;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    @Column(name = "source", nullable = false, length = 30, updatable = false)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ExchangeRate() {
    }

    ExchangeRate(UUID userId, String base, String quote, BigDecimal rate, LocalDate rateDate, String source) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.base = base;
        this.quote = quote;
        this.rate = rate;
        this.rateDate = rateDate;
        this.source = source;
    }

    void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getBase() {
        return base;
    }

    public String getQuote() {
        return quote;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    boolean isManual() {
        return MANUAL.equals(source);
    }
}
