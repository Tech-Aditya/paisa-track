package com.paisatrack.modules.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountRef;
import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.recurring.dto.ConfirmOccurrenceRequest;
import com.paisatrack.modules.recurring.dto.CreateRecurringRequest;
import com.paisatrack.modules.recurring.dto.OccurrenceResponse;
import com.paisatrack.modules.recurring.dto.RecurringRuleResponse;
import com.paisatrack.modules.recurring.dto.RecurringSummaryResponse;
import com.paisatrack.modules.recurring.dto.UpdateRecurringRequest;
import com.paisatrack.modules.transaction.TransactionChangedEvent;
import com.paisatrack.modules.transaction.TransactionService;
import com.paisatrack.modules.transaction.dto.ExpenseRequest;
import com.paisatrack.modules.transaction.dto.IncomeRequest;
import com.paisatrack.modules.transaction.dto.TransactionResponse;
import com.paisatrack.modules.transaction.dto.TransferRequest;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.money.MoneyRules;

/**
 * Recurring rules and their occurrences. Occurrences are generated ahead of time as plans; they
 * reach balances and totals only when confirmed, which records a normal transaction. Generation
 * starts from the day a rule is added (or resumed), and a unique (rule, due date) key makes it
 * safe to repeat. Unconfirmed occurrences stay overdue until confirmed or skipped.
 */
@Service
public class RecurringService {

    static final String AUDIT_ENTITY = "RECURRING";
    /** Occurrences are generated this far ahead, enough for any reminder window (max 30 days). */
    static final int HORIZON_DAYS = 35;
    private static final int MAX_PER_RUN = 400;
    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);

    private final RecurringRuleRepository rules;
    private final RecurringOccurrenceRepository occurrences;
    private final TransactionService transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final Clock clock;

    RecurringService(RecurringRuleRepository rules, RecurringOccurrenceRepository occurrences, TransactionService transactions,
                     AccountService accounts, CategoryService categories, UserPreferencesService preferences,
                     AuditService audit, IdempotencyService idempotency, Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.preferences = preferences;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public List<RecurringRuleResponse> list(UUID userId, boolean includeStopped) {
        List<RecurringRule> all = rules.findAllByUser(userId).stream()
                .filter(r -> includeStopped || r.getStatus() != RuleStatus.STOPPED).toList();
        return toResponses(userId, all);
    }

    @Transactional(readOnly = true)
    public RecurringRuleResponse get(UUID userId, UUID ruleId) {
        return toResponses(userId, List.of(load(userId, ruleId))).getFirst();
    }

    /** Every occurrence of a rule, latest first. */
    @Transactional(readOnly = true)
    public List<OccurrenceResponse> occurrences(UUID userId, UUID ruleId) {
        RecurringRule rule = load(userId, ruleId);
        LocalDate today = preferences.today(userId);
        return occurrences.findByRuleIdOrderByDueDateDesc(ruleId).stream().map(o -> toResponse(o, rule, today)).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(UUID userId, UUID ruleId) {
        load(userId, ruleId);
        return audit.history(userId, AUDIT_ENTITY, ruleId);
    }

    /** Occurrences of all rules due in a date range (default: a month either side of today). */
    @Transactional(readOnly = true)
    public List<OccurrenceResponse> occurrencesBetween(UUID userId, LocalDate from, LocalDate to, OccurrenceResponse.Status status) {
        LocalDate today = preferences.today(userId);
        LocalDate start = from != null ? from : today.minusDays(31);
        LocalDate end = to != null ? to : today.plusDays(31);
        if (start.isAfter(end)) {
            throw new FieldValidationException("from", "must not be after 'to'");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new FieldValidationException("to", "range can be at most one year");
        }
        return toOccurrenceResponses(userId, occurrences.findInRange(userId, start, end, null), today).stream()
                .filter(o -> status == null || o.status() == status)
                .toList();
    }

    /** Still expected: overdue, plus due within {@code days}. Totals are per currency. */
    @Transactional(readOnly = true)
    public RecurringSummaryResponse summary(UUID userId, int days) {
        LocalDate today = preferences.today(userId);
        List<OccurrenceResponse> expected = toOccurrenceResponses(userId,
                occurrences.findExpectedUntil(userId, today.plusDays(days)), today);
        Map<String, List<OccurrenceResponse>> byCurrency = expected.stream()
                .collect(Collectors.groupingBy(OccurrenceResponse::currency, TreeMap::new, Collectors.toList()));
        List<RecurringSummaryResponse.CurrencyTotals> totals = byCurrency.entrySet().stream().map(e -> {
            String c = e.getKey();
            BigDecimal out = sum(e.getValue().stream().filter(o -> o.type() != RecurringType.INCOME));
            BigDecimal in = sum(e.getValue().stream().filter(o -> o.type() == RecurringType.INCOME));
            return new RecurringSummaryResponse.CurrencyTotals(c, MoneyRules.scaled(out, c), MoneyRules.scaled(in, c),
                    (int) e.getValue().stream().filter(o -> o.status() == OccurrenceResponse.Status.OVERDUE).count(),
                    (int) e.getValue().stream().filter(o -> o.status() == OccurrenceResponse.Status.DUE).count());
        }).toList();
        return new RecurringSummaryResponse(days, totals, expected);
    }

    // ---- rule commands ----

    @Transactional
    public RecurringRuleResponse create(UUID userId, CreateRecurringRequest r, String idempotencyKey) {
        UUID id = idempotency.execute(userId, idempotencyKey, "CREATE_RECURRING", r, () -> {
            LocalDate today = preferences.today(userId);
            RecurringRule rule = new RecurringRule(userId, r.type());
            rule.rename(r.name());
            rule.setKind(r.kind());
            applyPosting(userId, rule, r.amount(), r.currency(), r.accountId(), r.toAccountId(), r.categoryId());
            rule.setDetails(r.paymentMethod(), clean(r.counterparty()), clean(r.description()), clean(r.notes()));
            applySchedule(rule, r.frequency(), r.interval() == null ? 1 : r.interval(),
                    r.startDate() != null ? r.startDate() : today, r.endDate());
            rule.restartFrom(today);
            rules.saveAndFlush(rule);
            int created = generate(rule, today.plusDays(HORIZON_DAYS));
            audit.record(userId, AUDIT_ENTITY, rule.getId(), AuditAction.CREATED, AuditService.diff(Map.of(), rule.snapshot()));
            log.info("Recurring rule created ruleId={} type={} frequency={} occurrences={}", rule.getId(), rule.getType(),
                    rule.getFrequency(), created);
            return rule.getId();
        });
        return get(userId, id);
    }

    /** Amount, account, category and schedule changes replace the expected occurrences from today on. */
    @Transactional
    public RecurringRuleResponse update(UUID userId, UUID ruleId, UpdateRecurringRequest r) {
        RecurringRule rule = lock(userId, ruleId);
        requireNotStopped(rule);
        Map<String, Object> before = rule.snapshot();
        if (r.name() != null) {
            rule.rename(r.name());
        }
        if (r.kind() != null) {
            rule.setKind(r.kind());
        }
        rule.setDetails(r.paymentMethod() != null ? r.paymentMethod() : rule.getPaymentMethod(),
                r.counterparty() != null ? clean(r.counterparty()) : rule.getCounterparty(),
                r.description() != null ? clean(r.description()) : rule.getDescription(),
                r.notes() != null ? clean(r.notes()) : rule.getNotes());

        boolean posting = Stream.of(r.amount(), r.accountId(), r.toAccountId(), r.categoryId()).anyMatch(v -> v != null);
        if (posting) {
            applyPosting(userId, rule, r.amount() != null ? r.amount() : rule.getAmount(), rule.getCurrency(),
                    r.accountId() != null ? r.accountId() : rule.getAccountId(),
                    r.toAccountId() != null ? r.toAccountId() : rule.getToAccountId(),
                    r.categoryId() != null ? r.categoryId() : rule.getCategoryId());
        }
        boolean schedule = Stream.of(r.frequency(), r.interval(), r.startDate(), r.endDate(), r.noEndDate()).anyMatch(v -> v != null);
        if (schedule) {
            LocalDate end = Boolean.TRUE.equals(r.noEndDate()) ? null : r.endDate() != null ? r.endDate() : rule.getEndDate();
            applySchedule(rule, r.frequency() != null ? r.frequency() : rule.getFrequency(),
                    r.interval() != null ? r.interval() : rule.getInterval(),
                    r.startDate() != null ? r.startDate() : rule.getStartDate(), end);
        }
        if (posting || schedule) {
            replan(rule);
        }
        rules.flush();
        Map<String, Object> changes = AuditService.diff(before, rule.snapshot());
        if (!changes.isEmpty()) {
            audit.record(userId, AUDIT_ENTITY, ruleId, AuditAction.UPDATED, changes);
        }
        return get(userId, ruleId);
    }

    /** No new occurrences; expected ones from today on are dropped. Overdue ones stay. */
    @Transactional
    public RecurringRuleResponse pause(UUID userId, UUID ruleId) {
        RecurringRule rule = lock(userId, ruleId);
        requireNotStopped(rule);
        if (rule.getStatus() == RuleStatus.ACTIVE) {
            rule.setStatus(RuleStatus.PAUSED);
            occurrences.deleteExpectedFrom(ruleId, preferences.today(userId));
            changed(userId, rule, AuditAction.PAUSED);
        }
        return get(userId, ruleId);
    }

    /** Generation restarts from today; dates missed while paused are not back-filled. */
    @Transactional
    public RecurringRuleResponse resume(UUID userId, UUID ruleId) {
        RecurringRule rule = lock(userId, ruleId);
        requireNotStopped(rule);
        if (rule.getStatus() == RuleStatus.PAUSED) {
            rule.setStatus(RuleStatus.ACTIVE);
            replan(rule);
            changed(userId, rule, AuditAction.RESUMED);
        }
        return get(userId, ruleId);
    }

    /** Ends the rule for good. Overdue occurrences stay so they can still be confirmed or skipped. */
    @Transactional
    public RecurringRuleResponse stop(UUID userId, UUID ruleId) {
        RecurringRule rule = lock(userId, ruleId);
        if (rule.getStatus() != RuleStatus.STOPPED) {
            rule.setStatus(RuleStatus.STOPPED);
            occurrences.deleteExpectedFrom(ruleId, preferences.today(userId));
            changed(userId, rule, AuditAction.STOPPED);
        }
        return get(userId, ruleId);
    }

    /** Only for rules never confirmed; otherwise stop them so history keeps its link. */
    @Transactional
    public void delete(UUID userId, UUID ruleId) {
        RecurringRule rule = lock(userId, ruleId);
        if (occurrences.hasConfirmed(ruleId)) {
            throw new ApiException(ErrorCode.RECURRING_HAS_CONFIRMED,
                    "This rule has confirmed transactions. Stop it instead, or reopen them first.");
        }
        rules.delete(rule);
        rules.flush();
        audit.record(userId, AUDIT_ENTITY, ruleId, AuditAction.DELETED, null);
        log.info("Recurring rule deleted ruleId={}", ruleId);
    }

    // ---- occurrence commands ----

    /** Records the actual transaction. The only way an occurrence affects balances and totals. */
    @Transactional
    public OccurrenceResponse confirm(UUID userId, UUID occurrenceId, ConfirmOccurrenceRequest r, String idempotencyKey) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        keyed.put("occurrenceId", occurrenceId);
        keyed.put("request", r);
        UUID id = idempotency.execute(userId, idempotencyKey, "CONFIRM_OCCURRENCE", keyed, () -> {
            RecurringOccurrence o = lockOccurrence(userId, occurrenceId);
            requireExpected(o);
            RecurringRule rule = load(userId, o.getRuleId());
            BigDecimal amount = r.amount() != null ? r.amount() : o.getExpectedAmount();
            UUID accountId = r.accountId() != null ? r.accountId() : rule.getAccountId();
            String description = rule.getDescription() != null ? rule.getDescription() : rule.getName();
            String notes = clean(r.notes());
            TransactionResponse tx = switch (rule.getType()) {
                case EXPENSE -> transactions.createExpense(userId, new ExpenseRequest(amount, rule.getCategoryId(), accountId,
                        rule.getCurrency(), r.transactionDate(), null, description, rule.getCounterparty(), rule.getPaymentMethod(),
                        notes), null);
                case INCOME -> transactions.createIncome(userId, new IncomeRequest(amount, rule.getCategoryId(), accountId,
                        rule.getCurrency(), r.transactionDate(), null, description, rule.getCounterparty(), rule.getPaymentMethod(),
                        notes, null, null), null);
                case TRANSFER -> transactions.createTransfer(userId, new TransferRequest(amount, accountId, rule.getToAccountId(),
                        rule.getCurrency(), r.transactionDate(), null, description, notes), null);
            };
            o.confirm(tx.id(), tx.amount(), clock.instant(), null);
            occurrences.flush();
            audit.record(userId, AUDIT_ENTITY, rule.getId(), AuditAction.CONFIRMED, Map.of("dueDate", o.getDueDate().toString(),
                    "transactionId", tx.id(), "amount", tx.amount().stripTrailingZeros().toPlainString()));
            log.info("Recurring occurrence confirmed occurrenceId={} transactionId={}", o.getId(), tx.id());
            return o.getId();
        });
        return occurrence(userId, id);
    }

    @Transactional
    public OccurrenceResponse skip(UUID userId, UUID occurrenceId, String reason) {
        RecurringOccurrence o = lockOccurrence(userId, occurrenceId);
        requireExpected(o);
        String note = clean(reason);
        o.skip(clock.instant(), note);
        occurrences.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dueDate", o.getDueDate().toString());
        details.put("reason", note);
        audit.record(userId, AUDIT_ENTITY, o.getRuleId(), AuditAction.SKIPPED, details);
        return occurrence(userId, occurrenceId);
    }

    /** Undoes a confirm (its transaction is voided) or a skip. Repeating it is harmless. */
    @Transactional
    public OccurrenceResponse reopen(UUID userId, UUID occurrenceId) {
        RecurringOccurrence o = lockOccurrence(userId, occurrenceId);
        if (o.getStatus() == RecurringOccurrence.Status.EXPECTED) {
            return occurrence(userId, occurrenceId);
        }
        if (o.getStatus() == RecurringOccurrence.Status.CONFIRMED) {
            transactions.voidTransaction(userId, o.getTransactionId(), "Recurring payment reopened");
        }
        o.reopen(false);
        occurrences.flush();
        audit.record(userId, AUDIT_ENTITY, o.getRuleId(), AuditAction.REOPENED, Map.of("dueDate", o.getDueDate().toString()));
        return occurrence(userId, occurrenceId);
    }

    // ---- generation ----

    /**
     * Generates the user's expected occurrences up to the horizon. Safe to run any number of times,
     * in parallel too: active rules are row-locked and inserts skip existing (rule, date) pairs.
     */
    @Transactional
    public int generate(UUID userId) {
        LocalDate horizon = preferences.today(userId).plusDays(HORIZON_DAYS);
        int created = 0;
        for (RecurringRule rule : rules.findActiveForUpdate(userId)) {
            created += generate(rule, horizon);
        }
        return created;
    }

    /** Keeps an occurrence in step when the user edits, voids or restores its transaction directly. */
    @EventListener
    void onTransactionChanged(TransactionChangedEvent event) {
        occurrences.findByUserIdAndTransactionId(event.userId(), event.transactionId()).ifPresent(o -> {
            switch (event.change()) {
                case VOIDED -> {
                    if (o.getStatus() == RecurringOccurrence.Status.CONFIRMED) {
                        o.reopen(true);
                    }
                }
                case RESTORED -> {
                    if (o.getStatus() == RecurringOccurrence.Status.EXPECTED) {
                        o.syncConfirmed(event.amount(), clock.instant());
                    }
                }
                case UPDATED -> {
                    if (o.getStatus() == RecurringOccurrence.Status.CONFIRMED) {
                        o.syncConfirmed(event.amount(), clock.instant());
                    }
                }
            }
        });
    }

    private int generate(RecurringRule rule, LocalDate horizon) {
        int created = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            LocalDate due = rule.dateOf(rule.getNextIndex());
            if (due.isAfter(horizon) || (rule.getEndDate() != null && due.isAfter(rule.getEndDate()))) {
                break;
            }
            created += occurrences.insertIfAbsent(rule.getUserId(), rule.getId(), due, rule.getAmount());
            rule.advance();
        }
        return created;
    }

    /** Drops expected occurrences from today on and, if active, generates them again from the current terms. */
    private void replan(RecurringRule rule) {
        LocalDate today = preferences.today(rule.getUserId());
        occurrences.deleteExpectedFrom(rule.getId(), today);
        rule.restartFrom(today);
        if (rule.getStatus() == RuleStatus.ACTIVE) {
            generate(rule, today.plusDays(HORIZON_DAYS));
        }
    }

    // ---- validation ----

    private void applyPosting(UUID userId, RecurringRule rule, BigDecimal amount, String currency, UUID accountId,
                              UUID toAccountId, UUID categoryId) {
        AccountRef account = accounts.requireForTransaction(userId, accountId, "accountId", rule.getAccountId());
        if (currency != null && !currency.equals(account.currency())) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH, "Account '" + account.name() + "' uses " + account.currency()
                    + ", not " + currency);
        }
        String c = account.currency();
        MoneyRules.requireValidAmount("amount", amount, c);
        UUID to = null;
        UUID category = null;
        if (rule.getType() == RecurringType.TRANSFER) {
            if (toAccountId == null) {
                throw new FieldValidationException("toAccountId", "is required for a transfer");
            }
            AccountRef destination = accounts.requireForTransaction(userId, toAccountId, "toAccountId", rule.getToAccountId());
            if (destination.id().equals(account.id())) {
                throw new FieldValidationException("toAccountId", "must be a different account");
            }
            if (!destination.currency().equals(c)) {
                throw new ApiException(ErrorCode.CURRENCY_MISMATCH, "Both accounts must use the same currency");
            }
            to = destination.id();
        } else {
            if (categoryId == null) {
                throw new FieldValidationException("categoryId", "is required");
            }
            category = categories.requireForTransaction(userId, categoryId, rule.getType().categoryType(), rule.getCategoryId()).id();
        }
        rule.setMoney(amount, c);
        rule.setAccounts(account.id(), to);
        rule.setCategoryId(category);
    }

    private static void applySchedule(RecurringRule rule, Frequency frequency, int interval, LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw new FieldValidationException("endDate", "can't be before the start date");
        }
        rule.setSchedule(frequency, interval, start, end);
    }

    private static void requireNotStopped(RecurringRule rule) {
        if (rule.getStatus() == RuleStatus.STOPPED) {
            throw new ApiException(ErrorCode.RECURRING_STOPPED, "This rule is stopped. Create a new one instead.");
        }
    }

    private static void requireExpected(RecurringOccurrence o) {
        if (o.getStatus() != RecurringOccurrence.Status.EXPECTED) {
            throw new ApiException(ErrorCode.OCCURRENCE_RESOLVED,
                    "This occurrence is already " + o.getStatus().name().toLowerCase() + ". Reopen it first.");
        }
    }

    private void changed(UUID userId, RecurringRule rule, AuditAction action) {
        rules.flush();
        audit.record(userId, AUDIT_ENTITY, rule.getId(), action, null);
        log.info("Recurring rule {} ruleId={}", action.name().toLowerCase(), rule.getId());
    }

    // ---- loading and mapping ----

    private RecurringRule load(UUID userId, UUID ruleId) {
        return rules.findByIdAndUserId(ruleId, userId).orElseThrow(() -> new ResourceNotFoundException("Recurring rule"));
    }

    private RecurringRule lock(UUID userId, UUID ruleId) {
        return rules.findForUpdate(ruleId, userId).orElseThrow(() -> new ResourceNotFoundException("Recurring rule"));
    }

    private RecurringOccurrence lockOccurrence(UUID userId, UUID occurrenceId) {
        return occurrences.findForUpdate(occurrenceId, userId).orElseThrow(() -> new ResourceNotFoundException("Occurrence"));
    }

    private OccurrenceResponse occurrence(UUID userId, UUID occurrenceId) {
        RecurringOccurrence o = occurrences.findById(occurrenceId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Occurrence"));
        return toResponse(o, load(userId, o.getRuleId()), preferences.today(userId));
    }

    private List<RecurringRuleResponse> toResponses(UUID userId, List<RecurringRule> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = preferences.today(userId);
        Map<UUID, RecurringRule> byId = list.stream().collect(Collectors.toMap(RecurringRule::getId, Function.identity()));
        Map<UUID, List<RecurringOccurrence>> expected = occurrences.findExpectedByRules(byId.keySet()).stream()
                .collect(Collectors.groupingBy(RecurringOccurrence::getRuleId));
        Set<UUID> accountIds = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        list.forEach(r -> {
            accountIds.add(r.getAccountId());
            if (r.getToAccountId() != null) {
                accountIds.add(r.getToAccountId());
            }
            if (r.getCategoryId() != null) {
                categoryIds.add(r.getCategoryId());
            }
        });
        Map<UUID, AccountRef> accountRefs = accounts.refs(userId, accountIds);
        Map<UUID, CategoryRef> categoryRefs = categories.refs(userId, categoryIds);
        return list.stream().map(r -> {
            List<RecurringOccurrence> open = expected.getOrDefault(r.getId(), List.of());
            OccurrenceResponse next = open.stream().min(Comparator.comparing(RecurringOccurrence::getDueDate))
                    .map(o -> toResponse(o, r, today)).orElse(null);
            int overdue = (int) open.stream().filter(o -> o.getDueDate().isBefore(today)).count();
            return new RecurringRuleResponse(r.getId(), r.getName(), r.getKind(), r.getType(),
                    MoneyRules.scaled(r.getAmount(), r.getCurrency()), r.getCurrency(),
                    accountRef(accountRefs.get(r.getAccountId())),
                    r.getToAccountId() == null ? null : accountRef(accountRefs.get(r.getToAccountId())),
                    r.getCategoryId() == null ? null : categoryRef(categoryRefs.get(r.getCategoryId())), r.getPaymentMethod(), r.getCounterparty(),
                    r.getDescription(), r.getNotes(), r.getFrequency(), r.getInterval(), r.getStartDate(), r.getEndDate(),
                    r.getStatus(), next, overdue, r.getCreatedAt(), r.getUpdatedAt());
        }).toList();
    }

    private List<OccurrenceResponse> toOccurrenceResponses(UUID userId, List<RecurringOccurrence> list, LocalDate today) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecurringRule> byId = rules.findAllById(list.stream().map(RecurringOccurrence::getRuleId).collect(Collectors.toSet()))
                .stream().filter(r -> r.getUserId().equals(userId))
                .collect(Collectors.toMap(RecurringRule::getId, Function.identity()));
        return list.stream().filter(o -> byId.containsKey(o.getRuleId()))
                .map(o -> toResponse(o, byId.get(o.getRuleId()), today)).toList();
    }

    private static OccurrenceResponse toResponse(RecurringOccurrence o, RecurringRule rule, LocalDate today) {
        String c = rule.getCurrency();
        return new OccurrenceResponse(o.getId(), rule.getId(), rule.getName(), rule.getKind(), rule.getType(), c,
                o.getDueDate(), MoneyRules.scaled(o.getExpectedAmount(), c), status(o, today),
                o.getActualAmount() == null ? null : MoneyRules.scaled(o.getActualAmount(), c),
                o.getStatus() == RecurringOccurrence.Status.CONFIRMED ? o.getTransactionId() : null, o.getResolvedAt(), o.getNote());
    }

    static OccurrenceResponse.Status status(RecurringOccurrence o, LocalDate today) {
        return switch (o.getStatus()) {
            case CONFIRMED -> OccurrenceResponse.Status.CONFIRMED;
            case SKIPPED -> OccurrenceResponse.Status.SKIPPED;
            case EXPECTED -> o.getDueDate().isBefore(today) ? OccurrenceResponse.Status.OVERDUE
                    : o.getDueDate().isEqual(today) ? OccurrenceResponse.Status.DUE : OccurrenceResponse.Status.UPCOMING;
        };
    }

    private static RecurringRuleResponse.Ref accountRef(AccountRef ref) {
        return ref == null ? null : new RecurringRuleResponse.Ref(ref.id(), ref.name());
    }

    private static RecurringRuleResponse.Ref categoryRef(CategoryRef ref) {
        if (ref == null) {
            return null;
        }
        return new RecurringRuleResponse.Ref(ref.id(), ref.parentName() == null ? ref.name() : ref.parentName() + " › " + ref.name());
    }

    private static BigDecimal sum(Stream<OccurrenceResponse> items) {
        return items.map(OccurrenceResponse::expectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
