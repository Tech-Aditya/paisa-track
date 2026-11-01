package com.paisatrack.modules.debt;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.debt.dto.CreateDebtRequest;
import com.paisatrack.modules.debt.dto.DebtResponse;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.modules.debt.dto.RepaymentRequest;
import com.paisatrack.modules.debt.dto.RepaymentResponse;
import com.paisatrack.modules.debt.dto.UpdateDebtRequest;
import com.paisatrack.modules.people.PersonRef;
import com.paisatrack.modules.people.PersonService;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.money.MoneyFormat;
import com.paisatrack.platform.money.MoneyRules;

/**
 * Money lent and borrowed. Tracking only: nothing here changes account balances, income or
 * expenses. Outstanding = principal - active repayments - written-off amount, always derived.
 * Changes to one debt run under a row lock, so concurrent repayments can't overshoot.
 */
@Service
public class DebtService {

    static final String AUDIT_ENTITY = "DEBT";
    static final String BASIS = "Money lent and borrowed is tracked separately. It is not part of account balances, "
            + "net worth, income or spending.";
    private static final Logger log = LoggerFactory.getLogger(DebtService.class);

    private final DebtRepository debts;
    private final DebtRepaymentRepository repayments;
    private final PersonService people;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final Clock clock;

    DebtService(DebtRepository debts, DebtRepaymentRepository repayments, PersonService people,
                UserPreferencesService preferences, AuditService audit, IdempotencyService idempotency, Clock clock) {
        this.debts = debts;
        this.repayments = repayments;
        this.people = people;
        this.preferences = preferences;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---- queries ----

    /** Records of one direction, newest first. {@code status} filters on the derived status; null means all. */
    @Transactional(readOnly = true)
    public List<DebtResponse> list(UUID userId, DebtDirection direction, DebtStatus status, UUID personId) {
        List<Debt> all = debts.findAllActive(userId).stream()
                .filter(d -> d.getDirection() == direction)
                .filter(d -> personId == null || d.getPersonId().equals(personId))
                .toList();
        return toResponses(userId, all, false).stream()
                .filter(r -> status == null || r.status() == status)
                .toList();
    }

    @Transactional(readOnly = true)
    public DebtResponse get(UUID userId, DebtDirection direction, UUID debtId) {
        return toResponses(userId, List.of(load(userId, direction, debtId)), true).getFirst();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(UUID userId, DebtDirection direction, UUID debtId) {
        load(userId, direction, debtId);
        return audit.history(userId, AUDIT_ENTITY, debtId);
    }

    @Transactional(readOnly = true)
    public RepaymentResponse getRepayment(UUID userId, UUID repaymentId) {
        DebtRepayment r = repayments.findByIdAndUserId(repaymentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repayment"));
        Debt debt = debts.findActive(r.getDebtId(), userId).orElseThrow(() -> new ResourceNotFoundException("Repayment"));
        return toResponse(r, debt.getCurrency());
    }

    /** Totals per currency, both directions. */
    @Transactional(readOnly = true)
    public DebtSummaryResponse summary(UUID userId) {
        Map<String, List<DebtResponse>> byCurrency = toResponses(userId, debts.findAllActive(userId), false).stream()
                .collect(Collectors.groupingBy(DebtResponse::currency, TreeMap::new, Collectors.toList()));
        List<DebtSummaryResponse.CurrencyTotals> totals = byCurrency.entrySet().stream().map(e -> {
            String currency = e.getKey();
            List<DebtResponse> list = e.getValue();
            return new DebtSummaryResponse.CurrencyTotals(currency,
                    MoneyRules.scaled(sum(list, DebtDirection.LENT, false), currency),
                    MoneyRules.scaled(sum(list, DebtDirection.BORROWED, false), currency),
                    MoneyRules.scaled(sum(list, DebtDirection.LENT, true), currency),
                    MoneyRules.scaled(sum(list, DebtDirection.BORROWED, true), currency),
                    (int) list.stream().filter(d -> d.direction() == DebtDirection.LENT && isOpen(d)).count(),
                    (int) list.stream().filter(d -> d.direction() == DebtDirection.BORROWED && isOpen(d)).count(),
                    (int) list.stream().filter(DebtResponse::overdue).count());
        }).toList();
        return new DebtSummaryResponse(BASIS, totals);
    }

    /**
     * Open records that are overdue, due within {@code days}, or whose reminder date falls within
     * {@code days}. Soonest first. The notifications phase builds on this.
     */
    @Transactional(readOnly = true)
    public List<DebtResponse> reminders(UUID userId, int days) {
        LocalDate horizon = preferences.today(userId).plusDays(days);
        return toResponses(userId, debts.findAllActive(userId), false).stream()
                .filter(DebtService::isOpen)
                .filter(d -> (d.dueDate() != null && !d.dueDate().isAfter(horizon))
                        || (d.remindOn() != null && !d.remindOn().isAfter(horizon)))
                .sorted(Comparator.comparing(DebtResponse::dueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ---- commands ----

    @Transactional
    public DebtResponse create(UUID userId, DebtDirection direction, CreateDebtRequest request, String idempotencyKey) {
        UUID id = idempotency.execute(userId, idempotencyKey, "CREATE_DEBT_" + direction, request, () -> {
            PersonRef person = people.resolve(userId, request.personId(), request.personName());
            String currency = request.currency() != null ? request.currency() : preferences.defaultCurrency(userId);
            MoneyRules.requireValidAmount("amount", request.amount(), currency);
            LocalDate today = preferences.today(userId);
            LocalDate start = request.startDate() != null ? request.startDate() : today;
            requireNotFuture("startDate", start, today);
            requireDueAfterStart(request.dueDate(), start);

            Debt debt = new Debt(userId, person.id(), direction, request.amount(), currency, start);
            debt.setDueDate(request.dueDate());
            debt.setRemindOn(request.remindOn());
            debt.setDescription(clean(request.description()));
            debt.setNotes(clean(request.notes()));
            debt.setPaymentMethod(request.paymentMethod());
            debts.saveAndFlush(debt);
            audit.record(userId, AUDIT_ENTITY, debt.getId(), AuditAction.CREATED, AuditService.diff(Map.of(), debt.snapshot()));
            log.info("Debt created debtId={} direction={}", debt.getId(), direction);
            return debt.getId();
        });
        return get(userId, direction, id);
    }

    @Transactional
    public DebtResponse update(UUID userId, DebtDirection direction, UUID debtId, UpdateDebtRequest request) {
        Debt debt = lock(userId, direction, debtId);
        Map<String, Object> before = debt.snapshot();
        List<DebtRepayment> active = activeRepayments(debtId);

        if (request.personId() != null) {
            debt.setPersonId(people.require(userId, request.personId()).id());
        }
        if (request.amount() != null) {
            if (debt.isWrittenOff()) {
                throw new ApiException(ErrorCode.DEBT_CLOSED, "This record is written off. Reopen it before changing the amount.");
            }
            MoneyRules.requireValidAmount("amount", request.amount(), debt.getCurrency());
            BigDecimal repaid = sumOf(active);
            if (request.amount().compareTo(repaid) < 0) {
                throw new FieldValidationException("amount",
                        "can't be less than what was already repaid (" + MoneyRules.scaled(repaid, debt.getCurrency()) + ")");
            }
            debt.setPrincipal(request.amount());
        }
        if (request.startDate() != null) {
            requireNotFuture("startDate", request.startDate(), preferences.today(userId));
            active.stream().map(DebtRepayment::getRepaymentDate).min(Comparator.naturalOrder())
                    .filter(first -> first.isBefore(request.startDate()))
                    .ifPresent(first -> {
                        throw new FieldValidationException("startDate", "can't be after the first repayment (" + first + ")");
                    });
            debt.setStartDate(request.startDate());
        }
        if (Boolean.TRUE.equals(request.clearDueDate())) {
            debt.setDueDate(null);
        } else if (request.dueDate() != null) {
            debt.setDueDate(request.dueDate());
        }
        requireDueAfterStart(debt.getDueDate(), debt.getStartDate());
        if (request.remindOn() != null) {
            debt.setRemindOn(request.remindOn());
        }
        if (request.description() != null) {
            debt.setDescription(clean(request.description()));
        }
        if (request.notes() != null) {
            debt.setNotes(clean(request.notes()));
        }
        if (request.paymentMethod() != null) {
            debt.setPaymentMethod(request.paymentMethod());
        }
        debts.flush();
        Map<String, Object> changes = AuditService.diff(before, debt.snapshot());
        if (!changes.isEmpty()) {
            audit.record(userId, AUDIT_ENTITY, debtId, AuditAction.UPDATED, changes);
        }
        return get(userId, direction, debtId);
    }

    /** Removes a record entered by mistake. Records with repayments must keep their history. */
    @Transactional
    public void delete(UUID userId, DebtDirection direction, UUID debtId) {
        Debt debt = lock(userId, direction, debtId);
        if (repayments.existsByDebtIdAndVoidedAtIsNull(debtId)) {
            throw new ApiException(ErrorCode.DEBT_HAS_REPAYMENTS,
                    "This record has repayments. Void them first, or write it off instead.");
        }
        debt.markDeleted(clock.instant());
        debts.flush();
        audit.record(userId, AUDIT_ENTITY, debtId, AuditAction.DELETED, null);
        log.info("Debt deleted debtId={}", debtId);
    }

    @Transactional
    public DebtResponse repay(UUID userId, DebtDirection direction, UUID debtId, RepaymentRequest request, String idempotencyKey) {
        // The debt id is part of the hashed request, so a key reused on another debt is rejected, not replayed.
        idempotency.execute(userId, idempotencyKey, "REPAY_DEBT", Map.of("debtId", debtId, "repayment", request), () -> {
            Debt debt = lock(userId, direction, debtId);
            if (debt.isWrittenOff()) {
                throw new ApiException(ErrorCode.DEBT_CLOSED, "This record is written off. Reopen it to record a repayment.");
            }
            String currency = debt.getCurrency();
            MoneyRules.requireValidAmount("amount", request.amount(), currency);
            BigDecimal outstanding = debt.getPrincipal().subtract(sumOf(activeRepayments(debtId)));
            if (outstanding.signum() <= 0) {
                throw new ApiException(ErrorCode.DEBT_CLOSED, "This record is already fully repaid.");
            }
            if (request.amount().compareTo(outstanding) > 0) {
                throw new ApiException(ErrorCode.REPAYMENT_EXCEEDS_OUTSTANDING,
                        "Repayment is more than the " + MoneyFormat.format(outstanding, currency) + " still outstanding");
            }
            LocalDate today = preferences.today(userId);
            LocalDate date = request.repaymentDate() != null ? request.repaymentDate() : today;
            requireNotFuture("repaymentDate", date, today);
            if (date.isBefore(debt.getStartDate())) {
                throw new FieldValidationException("repaymentDate", "can't be before the record's date (" + debt.getStartDate() + ")");
            }
            DebtRepayment repayment = repayments.saveAndFlush(new DebtRepayment(userId, debtId, request.amount(), date,
                    request.paymentMethod(), clean(request.notes()), clock.instant()));
            audit.record(userId, AUDIT_ENTITY, debtId, AuditAction.REPAID, Map.of(
                    "repaymentId", repayment.getId(),
                    "amount", request.amount().stripTrailingZeros().toPlainString(),
                    "repaymentDate", date.toString()));
            log.info("Repayment recorded debtId={} repaymentId={}", debtId, repayment.getId());
            return repayment.getId();
        });
        return get(userId, direction, debtId);
    }

    /** Undoes a repayment entered by mistake; it stays in the history as voided. Repeating it is harmless. */
    @Transactional
    public DebtResponse voidRepayment(UUID userId, UUID repaymentId, String reason) {
        DebtRepayment repayment = repayments.findByIdAndUserId(repaymentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repayment"));
        Debt debt = debts.findForUpdate(repayment.getDebtId(), userId).orElseThrow(() -> new ResourceNotFoundException("Repayment"));
        if (!repayment.isVoided()) {
            if (debt.isWrittenOff()) {
                throw new ApiException(ErrorCode.DEBT_CLOSED, "This record is written off. Reopen it before voiding a repayment.");
            }
            String cleanReason = clean(reason);
            repayment.markVoided(clock.instant(), cleanReason);
            repayments.flush();
            Map<String, Object> details = new HashMap<>();
            details.put("repaymentId", repaymentId);
            details.put("amount", repayment.getAmount().stripTrailingZeros().toPlainString());
            if (cleanReason != null) {
                details.put("reason", cleanReason);
            }
            audit.record(userId, AUDIT_ENTITY, debt.getId(), AuditAction.REPAYMENT_VOIDED, details);
            log.info("Repayment voided debtId={} repaymentId={}", debt.getId(), repaymentId);
        }
        return get(userId, debt.getDirection(), debt.getId());
    }

    /** Closes the record with whatever is still outstanding. Not income or expense. Repeating it is harmless. */
    @Transactional
    public DebtResponse writeOff(UUID userId, DebtDirection direction, UUID debtId, String reason) {
        Debt debt = lock(userId, direction, debtId);
        if (!debt.isWrittenOff()) {
            BigDecimal outstanding = debt.getPrincipal().subtract(sumOf(activeRepayments(debtId)));
            if (outstanding.signum() <= 0) {
                throw new ApiException(ErrorCode.DEBT_CLOSED, "Nothing is outstanding, so there is nothing to write off.");
            }
            debt.writeOff(outstanding, clock.instant(), clean(reason));
            debts.flush();
            audit.record(userId, AUDIT_ENTITY, debtId, AuditAction.WRITTEN_OFF,
                    Map.of("amount", outstanding.stripTrailingZeros().toPlainString()));
            log.info("Debt written off debtId={}", debtId);
        }
        return get(userId, direction, debtId);
    }

    @Transactional
    public DebtResponse reopen(UUID userId, DebtDirection direction, UUID debtId) {
        Debt debt = lock(userId, direction, debtId);
        if (debt.isWrittenOff()) {
            debt.reopen();
            debts.flush();
            audit.record(userId, AUDIT_ENTITY, debtId, AuditAction.REOPENED, null);
        }
        return get(userId, direction, debtId);
    }

    // ---- internals ----

    private Debt load(UUID userId, DebtDirection direction, UUID debtId) {
        return debts.findActive(debtId, userId).filter(d -> d.getDirection() == direction)
                .orElseThrow(() -> notFound(direction));
    }

    private Debt lock(UUID userId, DebtDirection direction, UUID debtId) {
        return debts.findForUpdate(debtId, userId).filter(d -> d.getDirection() == direction)
                .orElseThrow(() -> notFound(direction));
    }

    private static ResourceNotFoundException notFound(DebtDirection direction) {
        return new ResourceNotFoundException(direction == DebtDirection.LENT ? "Lending record" : "Borrowing record");
    }

    private List<DebtRepayment> activeRepayments(UUID debtId) {
        return repayments.findByDebtIdOrderByRepaymentDateAscCreatedAtAsc(debtId).stream()
                .filter(r -> !r.isVoided()).toList();
    }

    private static BigDecimal sumOf(List<DebtRepayment> list) {
        return list.stream().map(DebtRepayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Totals(BigDecimal repaid, long count, LocalDate last) {
        static final Totals NONE = new Totals(BigDecimal.ZERO, 0, null);
    }

    private Map<UUID, Totals> totals(Collection<UUID> debtIds) {
        if (debtIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Totals> result = new HashMap<>();
        for (Object[] row : repayments.totalsFor(debtIds)) {
            result.put((UUID) row[0], new Totals((BigDecimal) row[1], (Long) row[2], (LocalDate) row[3]));
        }
        return result;
    }

    private List<DebtResponse> toResponses(UUID userId, List<Debt> list, boolean withRepayments) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = preferences.today(userId);
        Set<UUID> ids = list.stream().map(Debt::getId).collect(Collectors.toSet());
        Map<UUID, Totals> totals = totals(ids);
        Map<UUID, PersonRef> persons = people.refs(userId, list.stream().map(Debt::getPersonId).collect(Collectors.toSet()));
        List<DebtResponse> out = new ArrayList<>();
        for (Debt d : list) {
            Totals t = totals.getOrDefault(d.getId(), Totals.NONE);
            String c = d.getCurrency();
            BigDecimal writtenOff = d.getWrittenOffAmount() == null ? BigDecimal.ZERO : d.getWrittenOffAmount();
            BigDecimal outstanding = d.getPrincipal().subtract(t.repaid()).subtract(writtenOff).max(BigDecimal.ZERO);
            boolean overdue = !d.isWrittenOff() && outstanding.signum() > 0 && d.getDueDate() != null && today.isAfter(d.getDueDate());
            DebtStatus status = d.isWrittenOff() ? DebtStatus.WRITTEN_OFF
                    : outstanding.signum() == 0 ? DebtStatus.SETTLED
                    : overdue ? DebtStatus.OVERDUE
                    : t.repaid().signum() > 0 ? DebtStatus.PARTIALLY_REPAID
                    : DebtStatus.OPEN;
            PersonRef person = persons.get(d.getPersonId());
            List<RepaymentResponse> history = withRepayments
                    ? repayments.findByDebtIdOrderByRepaymentDateAscCreatedAtAsc(d.getId()).stream().map(r -> toResponse(r, c)).toList()
                    : null;
            out.add(new DebtResponse(d.getId(), d.getDirection(),
                    person == null ? null : new DebtResponse.PersonInfo(person.id(), person.name()),
                    MoneyRules.scaled(d.getPrincipal(), c), c, MoneyRules.scaled(t.repaid(), c), MoneyRules.scaled(writtenOff, c),
                    MoneyRules.scaled(outstanding, c), status, overdue,
                    overdue ? ChronoUnit.DAYS.between(d.getDueDate(), today) : 0,
                    d.getStartDate(), d.getDueDate(), d.getRemindOn(), d.getDescription(), d.getNotes(), d.getPaymentMethod(),
                    t.count(), t.last(), d.getWrittenOffAt(), d.getWriteOffReason(), history, d.getCreatedAt(), d.getUpdatedAt()));
        }
        return out;
    }

    private static RepaymentResponse toResponse(DebtRepayment r, String currency) {
        return new RepaymentResponse(r.getId(), r.getDebtId(), MoneyRules.scaled(r.getAmount(), currency), currency,
                r.getRepaymentDate(), r.getPaymentMethod(), r.getNotes(), r.isVoided(), r.getVoidedAt(), r.getVoidReason(),
                r.getCreatedAt());
    }

    private static boolean isOpen(DebtResponse d) {
        return d.status() != DebtStatus.SETTLED && d.status() != DebtStatus.WRITTEN_OFF;
    }

    private static BigDecimal sum(List<DebtResponse> list, DebtDirection direction, boolean overdueOnly) {
        return list.stream()
                .filter(d -> d.direction() == direction && (!overdueOnly || d.overdue()))
                .map(DebtResponse::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requireNotFuture(String field, LocalDate date, LocalDate today) {
        if (date.isAfter(today.plusDays(1))) {
            throw new FieldValidationException(field, "can't be in the future");
        }
    }

    private static void requireDueAfterStart(LocalDate due, LocalDate start) {
        if (due != null && due.isBefore(start)) {
            throw new FieldValidationException("dueDate", "can't be before the date the money changed hands");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
