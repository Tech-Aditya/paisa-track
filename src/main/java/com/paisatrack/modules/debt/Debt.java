package com.paisatrack.modules.debt;

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

import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.persistence.AuditableEntity;

/** Money lent or borrowed. The principal is the original amount; repayments are separate records. */
@Entity
@Table(name = "debts")
public class Debt extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private DebtDirection direction;

    @Column(name = "principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "remind_on")
    private LocalDate remindOn;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "written_off_amount", precision = 19, scale = 4)
    private BigDecimal writtenOffAmount;

    @Column(name = "written_off_at")
    private Instant writtenOffAt;

    @Column(name = "write_off_reason", length = 200)
    private String writeOffReason;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Debt() {
    }

    Debt(UUID userId, UUID personId, DebtDirection direction, BigDecimal principal, String currency, LocalDate startDate) {
        this.userId = userId;
        this.personId = personId;
        this.direction = direction;
        this.principal = principal;
        this.currency = currency;
        this.startDate = startDate;
    }

    boolean isWrittenOff() {
        return writtenOffAt != null;
    }

    void writeOff(BigDecimal amount, Instant at, String reason) {
        this.writtenOffAmount = amount;
        this.writtenOffAt = at;
        this.writeOffReason = reason;
    }

    void reopen() {
        this.writtenOffAmount = null;
        this.writtenOffAt = null;
        this.writeOffReason = null;
    }

    void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    /** Audited fields. */
    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("personId", personId);
        s.put("principal", principal.stripTrailingZeros().toPlainString());
        s.put("startDate", startDate.toString());
        s.put("dueDate", dueDate == null ? null : dueDate.toString());
        s.put("remindOn", remindOn == null ? null : remindOn.toString());
        s.put("description", description);
        s.put("notes", notes);
        s.put("paymentMethod", paymentMethod);
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public DebtDirection getDirection() {
        return direction;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getRemindOn() {
        return remindOn;
    }

    public String getDescription() {
        return description;
    }

    public String getNotes() {
        return notes;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getWrittenOffAmount() {
        return writtenOffAmount;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public String getWriteOffReason() {
        return writeOffReason;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    void setPersonId(UUID personId) {
        this.personId = personId;
    }

    void setPrincipal(BigDecimal principal) {
        this.principal = principal;
    }

    void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    void setRemindOn(LocalDate remindOn) {
        this.remindOn = remindOn;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
