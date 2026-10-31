package com.paisatrack.modules.recurring;

import java.math.BigDecimal;
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

import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.persistence.AuditableEntity;

/** A repeating expense, income or transfer. Its occurrences are plans until confirmed. */
@Entity
@Table(name = "recurring_rules")
public class RecurringRule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private RecurringKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private RecurringType type;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "counterparty", length = 100)
    private String counterparty;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 10)
    private Frequency frequency;

    @Column(name = "interval_count", nullable = false)
    private int interval;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private RuleStatus status;

    @Column(name = "next_index", nullable = false)
    private int nextIndex;

    protected RecurringRule() {
    }

    RecurringRule(UUID userId, RecurringType type) {
        this.userId = userId;
        this.type = type;
        this.status = RuleStatus.ACTIVE;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("kind", kind);
        s.put("amount", amount.stripTrailingZeros().toPlainString());
        s.put("currency", currency);
        s.put("accountId", accountId);
        s.put("toAccountId", toAccountId);
        s.put("categoryId", categoryId);
        s.put("paymentMethod", paymentMethod);
        s.put("counterparty", counterparty);
        s.put("description", description);
        s.put("notes", notes);
        s.put("frequency", frequency);
        s.put("interval", interval);
        s.put("startDate", startDate.toString());
        s.put("endDate", endDate == null ? null : endDate.toString());
        s.put("status", status);
        return s;
    }

    /** Due date of occurrence {@code index}. */
    LocalDate dateOf(int index) {
        return frequency.dateOf(startDate, interval, index);
    }

    /** Next generation starts at the first date on or after {@code date}. */
    void restartFrom(LocalDate date) {
        this.nextIndex = frequency.firstIndexOnOrAfter(startDate, interval, date);
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

    public RecurringKind getKind() {
        return kind;
    }

    public RecurringType getType() {
        return type;
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

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public String getDescription() {
        return description;
    }

    public String getNotes() {
        return notes;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public int getInterval() {
        return interval;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public RuleStatus getStatus() {
        return status;
    }

    int getNextIndex() {
        return nextIndex;
    }

    void advance() {
        nextIndex++;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setKind(RecurringKind kind) {
        this.kind = kind;
    }

    void setMoney(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    void setAccounts(UUID accountId, UUID toAccountId) {
        this.accountId = accountId;
        this.toAccountId = toAccountId;
    }

    void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    void setDetails(PaymentMethod paymentMethod, String counterparty, String description, String notes) {
        this.paymentMethod = paymentMethod;
        this.counterparty = counterparty;
        this.description = description;
        this.notes = notes;
    }

    void setSchedule(Frequency frequency, int interval, LocalDate startDate, LocalDate endDate) {
        this.frequency = frequency;
        this.interval = interval;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    void setStatus(RuleStatus status) {
        this.status = status;
    }
}
