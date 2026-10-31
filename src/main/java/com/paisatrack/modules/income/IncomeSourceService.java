package com.paisatrack.modules.income;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountRef;
import com.paisatrack.modules.account.AccountService;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.category.CategoryType;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.modules.income.dto.CreateIncomeSourceRequest;
import com.paisatrack.modules.income.dto.ExpectedIncomeResponse;
import com.paisatrack.modules.income.dto.ExpectedIncomeResponse.Status;
import com.paisatrack.modules.income.dto.IncomeSourceResponse;
import com.paisatrack.modules.income.dto.SetExpectationRequest;
import com.paisatrack.modules.income.dto.UpdateIncomeSourceRequest;
import com.paisatrack.modules.user.UserPreferencesService;

/**
 * Income sources and their monthly expectations. Expectations are plans only: nothing here creates
 * income. "Received" always comes from recorded INCOME transactions linked to the source.
 */
@Service
public class IncomeSourceService {

    static final String AUDIT_ENTITY = "INCOME_SOURCE";
    private static final Logger log = LoggerFactory.getLogger(IncomeSourceService.class);

    private final IncomeSourceRepository sources;
    private final IncomeExpectationRepository expectations;
    private final IncomeReceiptProvider receipts;
    private final AccountService accounts;
    private final CategoryService categories;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final Clock clock;

    IncomeSourceService(IncomeSourceRepository sources, IncomeExpectationRepository expectations,
                        IncomeReceiptProvider receipts, AccountService accounts, CategoryService categories,
                        UserPreferencesService preferences, AuditService audit, Clock clock) {
        this.sources = sources;
        this.expectations = expectations;
        this.receipts = receipts;
        this.accounts = accounts;
        this.categories = categories;
        this.preferences = preferences;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<IncomeSourceResponse> list(UUID userId, boolean includeInactive) {
        List<IncomeSource> all = sources.findByUserIdOrderByCreatedAt(userId).stream()
                .filter(s -> includeInactive || s.isActive())
                .toList();
        return toResponses(userId, all);
    }

    @Transactional(readOnly = true)
    public IncomeSourceResponse get(UUID userId, UUID sourceId) {
        return toResponses(userId, List.of(load(userId, sourceId))).getFirst();
    }

    @Transactional
    public IncomeSourceResponse create(UUID userId, CreateIncomeSourceRequest request) {
        requireUniqueName(userId, request.name(), null);
        AccountRef account = request.accountId() == null ? null
                : accounts.requireForTransaction(userId, request.accountId(), "accountId", null);
        String currency = resolveCurrency(userId, request.currency(), account);
        UUID categoryId = resolveCategory(userId, request.categoryId(), request.type(), null);
        LocalDate startMonth = (request.startMonth() != null ? request.startMonth() : currentMonth(userId)).atDay(1);
        if (request.expectedAmount() != null) {
            MoneyRules.requireValidAmount("expectedAmount", request.expectedAmount(), currency);
        }

        IncomeSource source = sources.saveAndFlush(new IncomeSource(userId, request.name(), request.type(), currency,
                categoryId, account == null ? null : account.id(), startMonth, blankToNull(request.notes())));
        if (request.expectedAmount() != null) {
            int day = request.expectedDay() != null ? request.expectedDay() : 1;
            expectations.save(new IncomeExpectation(source.getId(), startMonth, request.expectedAmount(), day, clock.instant()));
        }
        audit.record(userId, AUDIT_ENTITY, source.getId(), AuditAction.CREATED, null);
        log.info("Income source created sourceId={} type={}", source.getId(), source.getType());
        return get(userId, source.getId());
    }

    @Transactional
    public IncomeSourceResponse update(UUID userId, UUID sourceId, UpdateIncomeSourceRequest request) {
        IncomeSource source = load(userId, sourceId);
        if (request.name() != null) {
            requireUniqueName(userId, request.name(), sourceId);
            source.rename(request.name());
        }
        if (request.type() != null) {
            source.setType(request.type());
        }
        if (request.categoryId() != null) {
            source.setCategoryId(resolveCategory(userId, request.categoryId(), source.getType(), source.getCategoryId()));
        }
        if (request.accountId() != null) {
            AccountRef account = accounts.requireForTransaction(userId, request.accountId(), "accountId", source.getAccountId());
            if (!account.currency().equals(source.getCurrency())) {
                throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                        "Account '" + account.name() + "' uses " + account.currency() + ", this source uses " + source.getCurrency());
            }
            source.setAccountId(account.id());
        }
        if (request.notes() != null) {
            source.setNotes(blankToNull(request.notes()));
        }
        sources.flush();
        return get(userId, sourceId);
    }

    /** Sets the expected amount from a month onward; earlier months keep what was expected then. */
    @Transactional
    public IncomeSourceResponse setExpectation(UUID userId, UUID sourceId, SetExpectationRequest request) {
        IncomeSource source = load(userId, sourceId);
        LocalDate effective = (request.effectiveFrom() != null ? request.effectiveFrom() : currentMonth(userId)).atDay(1);
        if (effective.isBefore(source.getStartMonth())) {
            throw new FieldValidationException("effectiveFrom", "can't be before the source's start month");
        }
        if (request.amount() != null) {
            MoneyRules.requireValidAmount("amount", request.amount(), source.getCurrency());
        }
        IncomeExpectation previous = expectationFor(rowsOf(List.of(sourceId)).getOrDefault(sourceId, List.of()), effective);
        int day = request.expectedDay() != null ? request.expectedDay()
                : previous != null && previous.getExpectedDay() != null ? previous.getExpectedDay() : 1;

        IncomeExpectation row = expectations.findByIncomeSourceIdAndEffectiveMonth(sourceId, effective)
                .orElseGet(() -> new IncomeExpectation(sourceId, effective, request.amount(), day, clock.instant()));
        row.set(request.amount(), day);
        expectations.saveAndFlush(row);

        audit.record(userId, AUDIT_ENTITY, sourceId, AuditAction.UPDATED, AuditService.diff(
                expectationSnapshot(previous), Map.of(
                        "expectedAmount", request.amount() == null ? "none" : request.amount().stripTrailingZeros().toPlainString(),
                        "expectedDay", request.amount() == null ? "none" : String.valueOf(day),
                        "effectiveFrom", YearMonth.from(effective).toString())));
        log.info("Income expectation set sourceId={} from={}", sourceId, YearMonth.from(effective));
        return get(userId, sourceId);
    }

    @Transactional
    public IncomeSourceResponse setActive(UUID userId, UUID sourceId, boolean active) {
        IncomeSource source = load(userId, sourceId);
        if (source.isActive() != active) {
            source.setActive(active);
            sources.flush();
            audit.record(userId, AUDIT_ENTITY, sourceId, active ? AuditAction.ACTIVATED : AuditAction.DEACTIVATED, null);
        }
        return get(userId, sourceId);
    }

    /** Expected versus received for every active source in one month (defaults to this month). */
    @Transactional(readOnly = true)
    public List<ExpectedIncomeResponse> monthOverview(UUID userId, YearMonth month) {
        YearMonth target = month != null ? month : currentMonth(userId);
        List<IncomeSource> active = sources.findByUserIdOrderByCreatedAt(userId).stream()
                .filter(IncomeSource::isActive)
                .filter(s -> !target.atDay(1).isBefore(s.getStartMonth()))
                .toList();
        Map<UUID, List<IncomeExpectation>> rows = rowsOf(active.stream().map(IncomeSource::getId).toList());
        Map<UUID, Map<LocalDate, BigDecimal>> received = receipts.receivedByPeriod(userId, target.atDay(1), target.atDay(1));
        LocalDate today = preferences.today(userId);
        return active.stream()
                .map(s -> monthStatus(s, target, rows.getOrDefault(s.getId(), List.of()), received, today))
                .filter(r -> r.expectedAmount() != null || r.receivedAmount().signum() > 0)
                .toList();
    }

    /** Month-by-month record for one source over a calendar year (defaults to this year). */
    @Transactional(readOnly = true)
    public List<ExpectedIncomeResponse> history(UUID userId, UUID sourceId, Integer year) {
        IncomeSource source = load(userId, sourceId);
        LocalDate today = preferences.today(userId);
        int y = year != null ? year : today.getYear();
        YearMonth first = YearMonth.of(y, 1);
        YearMonth start = first.isBefore(YearMonth.from(source.getStartMonth())) ? YearMonth.from(source.getStartMonth()) : first;
        YearMonth end = YearMonth.of(y, 12);
        if (start.isAfter(end)) {
            return List.of();
        }
        List<IncomeExpectation> rows = rowsOf(List.of(sourceId)).getOrDefault(sourceId, List.of());
        Map<UUID, Map<LocalDate, BigDecimal>> received = receipts.receivedByPeriod(userId, start.atDay(1), end.atDay(1));
        List<ExpectedIncomeResponse> months = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            months.add(monthStatus(source, m, rows, received, today));
        }
        return months;
    }

    // ---- API for other modules ----

    /** Source for recording income: owned, and active unless it is {@code currentSourceId}. */
    @Transactional(readOnly = true)
    public IncomeSourceRef requireForIncome(UUID userId, UUID sourceId, UUID currentSourceId) {
        IncomeSource source = load(userId, sourceId);
        if (!source.isActive() && !source.getId().equals(currentSourceId)) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Income source '" + source.getName() + "' is deactivated");
        }
        return IncomeSourceRef.of(source);
    }

    @Transactional(readOnly = true)
    public Map<UUID, IncomeSourceRef> refs(UUID userId, Collection<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return sources.findByUserIdAndIdIn(userId, sourceIds).stream()
                .collect(Collectors.toMap(IncomeSource::getId, IncomeSourceRef::of));
    }

    // ---- internals ----

    private ExpectedIncomeResponse monthStatus(IncomeSource source, YearMonth month, List<IncomeExpectation> rows,
                                               Map<UUID, Map<LocalDate, BigDecimal>> received, LocalDate today) {
        String currency = source.getCurrency();
        IncomeExpectation expectation = expectationFor(rows, month.atDay(1));
        BigDecimal expected = expectation == null ? null : expectation.getAmount();
        BigDecimal got = received.getOrDefault(source.getId(), Map.of()).getOrDefault(month.atDay(1), BigDecimal.ZERO);
        LocalDate expectedDate = expected == null ? null
                : month.atDay(Math.min(expectation.getExpectedDay(), month.lengthOfMonth()));
        BigDecimal remaining = expected == null ? null : expected.subtract(got).max(BigDecimal.ZERO);
        return new ExpectedIncomeResponse(source.getId(), source.getName(), month, currency,
                MoneyRules.scaled(expected, currency), expectedDate, MoneyRules.scaled(got, currency),
                MoneyRules.scaled(remaining, currency), status(expected, got, expectedDate, today));
    }

    static Status status(BigDecimal expected, BigDecimal received, LocalDate expectedDate, LocalDate today) {
        if (expected == null) {
            return Status.NOT_EXPECTED;
        }
        if (received.compareTo(expected) >= 0) {
            return Status.RECEIVED;
        }
        if (received.signum() > 0) {
            return Status.PARTIALLY_RECEIVED;
        }
        return today.isAfter(expectedDate) ? Status.OVERDUE : Status.PENDING;
    }

    /** The row in effect for a month: the latest one starting on or before it. */
    private static IncomeExpectation expectationFor(List<IncomeExpectation> rowsByMonth, LocalDate month) {
        IncomeExpectation result = null;
        for (IncomeExpectation row : rowsByMonth) {
            if (row.getEffectiveMonth().isAfter(month)) {
                break;
            }
            result = row;
        }
        return result == null || result.getAmount() == null ? null : result;
    }

    private Map<UUID, List<IncomeExpectation>> rowsOf(Collection<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return expectations.findByIncomeSourceIdInOrderByEffectiveMonth(sourceIds).stream()
                .collect(Collectors.groupingBy(IncomeExpectation::getIncomeSourceId));
    }

    private List<IncomeSourceResponse> toResponses(UUID userId, List<IncomeSource> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        YearMonth month = currentMonth(userId);
        LocalDate today = preferences.today(userId);
        Map<UUID, List<IncomeExpectation>> rows = rowsOf(list.stream().map(IncomeSource::getId).toList());
        Map<UUID, Map<LocalDate, BigDecimal>> received = receipts.receivedByPeriod(userId, month.atDay(1), month.atDay(1));
        Map<UUID, CategoryRef> categoryRefs = categories.refs(userId,
                list.stream().map(IncomeSource::getCategoryId).collect(Collectors.toSet()));
        Set<UUID> accountIds = new HashSet<>();
        list.stream().map(IncomeSource::getAccountId).filter(Objects::nonNull).forEach(accountIds::add);
        Map<UUID, AccountRef> accountRefs = accounts.refs(userId, accountIds);

        return list.stream().map(s -> {
            List<IncomeExpectation> sourceRows = rows.getOrDefault(s.getId(), List.of());
            IncomeExpectation current = expectationFor(sourceRows, month.atDay(1));
            CategoryRef category = categoryRefs.get(s.getCategoryId());
            AccountRef account = s.getAccountId() == null ? null : accountRefs.get(s.getAccountId());
            boolean started = !month.atDay(1).isBefore(s.getStartMonth());
            return new IncomeSourceResponse(s.getId(), s.getName(), s.getType(), s.getCurrency(),
                    category == null ? null : new IncomeSourceResponse.Ref(category.id(), category.name()),
                    account == null ? null : new IncomeSourceResponse.Ref(account.id(), account.name()),
                    YearMonth.from(s.getStartMonth()), s.getNotes(), s.isActive(),
                    current == null ? null : new IncomeSourceResponse.Expectation(
                            MoneyRules.scaled(current.getAmount(), s.getCurrency()), current.getExpectedDay(),
                            YearMonth.from(current.getEffectiveMonth())),
                    started ? monthStatus(s, month, sourceRows, received, today) : null,
                    s.getCreatedAt(), s.getUpdatedAt());
        }).toList();
    }

    private String resolveCurrency(UUID userId, String requested, AccountRef account) {
        if (account != null) {
            if (requested != null && !requested.equals(account.currency())) {
                throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                        "Account '" + account.name() + "' uses " + account.currency() + ", not " + requested);
            }
            return account.currency();
        }
        return requested != null ? requested : preferences.defaultCurrency(userId);
    }

    private UUID resolveCategory(UUID userId, UUID categoryId, IncomeSourceType type, UUID currentCategoryId) {
        if (categoryId != null) {
            return categories.requireForTransaction(userId, categoryId, CategoryType.INCOME, currentCategoryId).id();
        }
        return categories.findActiveBySystemCode(userId, type.defaultCategoryCode())
                .map(CategoryRef::id)
                .orElseThrow(() -> new FieldValidationException("categoryId", "is required (no default category for " + type + ")"));
    }

    private YearMonth currentMonth(UUID userId) {
        return YearMonth.from(preferences.today(userId));
    }

    private IncomeSource load(UUID userId, UUID sourceId) {
        return sources.findByIdAndUserId(sourceId, userId).orElseThrow(() -> new ResourceNotFoundException("Income source"));
    }

    private void requireUniqueName(UUID userId, String name, UUID excludeId) {
        String trimmed = name.strip();
        boolean taken = excludeId == null
                ? sources.existsByUserIdAndNameIgnoreCase(userId, trimmed)
                : sources.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, trimmed, excludeId);
        if (taken) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "You already have an income source named '" + trimmed + "'");
        }
    }

    private static Map<String, Object> expectationSnapshot(IncomeExpectation e) {
        return Map.of(
                "expectedAmount", e == null ? "none" : e.getAmount().stripTrailingZeros().toPlainString(),
                "expectedDay", e == null ? "none" : String.valueOf(e.getExpectedDay()),
                "effectiveFrom", e == null ? "none" : YearMonth.from(e.getEffectiveMonth()).toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
