package com.paisatrack.modules.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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

import com.paisatrack.modules.budget.dto.BudgetResponse;
import com.paisatrack.modules.budget.dto.BudgetResponse.Status;
import com.paisatrack.modules.budget.dto.BudgetResponse.Utilization;
import com.paisatrack.modules.budget.dto.CreateBudgetRequest;
import com.paisatrack.modules.budget.dto.UpdateBudgetRequest;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.category.CategoryType;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.modules.transaction.TransactionTotalsService;
import com.paisatrack.modules.user.UserPreferencesService;

/**
 * Budgets and their utilization. Spending is read from actual EXPENSE transactions in the budget's
 * currency, so transfers, voided entries and expected payments never count. No rollover.
 */
@Service
public class BudgetService {

    static final List<Integer> DEFAULT_THRESHOLDS = List.of(75, 90, 100);
    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository repository;
    private final CategoryService categories;
    private final TransactionTotalsService totals;
    private final UserPreferencesService preferences;

    BudgetService(BudgetRepository repository, CategoryService categories, TransactionTotalsService totals,
                  UserPreferencesService preferences) {
        this.repository = repository;
        this.categories = categories;
        this.totals = totals;
        this.preferences = preferences;
    }

    /** Budgets with utilization for the period containing {@code date} (default: today). */
    @Transactional(readOnly = true)
    public List<BudgetResponse> list(UUID userId, LocalDate date, boolean includeInactive) {
        LocalDate day = date != null ? date : preferences.today(userId);
        List<Budget> budgets = repository.findByUserIdOrderByCreatedAt(userId).stream()
                .filter(b -> includeInactive || b.isActive())
                .toList();
        return toResponses(userId, budgets, day);
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(UUID userId, UUID budgetId, LocalDate date) {
        LocalDate day = date != null ? date : preferences.today(userId);
        return toResponses(userId, List.of(load(userId, budgetId)), day).getFirst();
    }

    @Transactional
    public BudgetResponse create(UUID userId, CreateBudgetRequest request) {
        String currency = request.currency() != null ? request.currency() : preferences.defaultCurrency(userId);
        MoneyRules.requireValidAmount("amount", request.amount(), currency);
        List<Integer> thresholds = validThresholds(request.alertThresholds());

        CategoryRef category = null;
        if (request.scope() == BudgetScope.CATEGORY) {
            if (request.categoryId() == null) {
                throw new FieldValidationException("categoryId", "is required for a CATEGORY budget");
            }
            category = categories.requireForTransaction(userId, request.categoryId(), CategoryType.EXPENSE, null);
        } else if (request.categoryId() != null) {
            throw new FieldValidationException("categoryId", "must be empty for an OVERALL budget");
        }

        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        if (request.period() == BudgetPeriod.CUSTOM) {
            if (start == null) {
                throw new FieldValidationException("startDate", "is required for a CUSTOM budget");
            }
            if (end == null) {
                throw new FieldValidationException("endDate", "is required for a CUSTOM budget");
            }
        } else if (start == null) {
            start = periodStart(request.period(), preferences.today(userId), weekStart(userId));
        }
        if (end != null && end.isBefore(start)) {
            throw new FieldValidationException("endDate", "must not be before startDate");
        }
        if (request.period() != BudgetPeriod.CUSTOM) {
            requireNoActiveDuplicate(userId, request.scope(), category == null ? null : category.id(), request.period(), currency, null);
        }

        String name = request.name() != null && !request.name().isBlank() ? request.name()
                : defaultName(category, request.period());
        Budget budget = repository.saveAndFlush(new Budget(userId, name, request.scope(),
                category == null ? null : category.id(), request.period(), request.amount(), currency, start, end, thresholds));
        log.info("Budget created budgetId={} scope={} period={}", budget.getId(), budget.getScope(), budget.getPeriod());
        return get(userId, budget.getId(), null);
    }

    @Transactional
    public BudgetResponse update(UUID userId, UUID budgetId, UpdateBudgetRequest request) {
        Budget budget = load(userId, budgetId);
        if (request.name() != null) {
            budget.rename(request.name());
        }
        if (request.amount() != null) {
            MoneyRules.requireValidAmount("amount", request.amount(), budget.getCurrency());
            budget.setAmount(request.amount());
        }
        if (request.endDate() != null) {
            if (request.endDate().isBefore(budget.getStartDate())) {
                throw new FieldValidationException("endDate", "must not be before startDate");
            }
            budget.setEndDate(request.endDate());
        }
        if (request.alertThresholds() != null) {
            budget.setAlertThresholds(validThresholds(request.alertThresholds()));
        }
        repository.flush();
        return get(userId, budgetId, null);
    }

    @Transactional
    public BudgetResponse setActive(UUID userId, UUID budgetId, boolean active) {
        Budget budget = load(userId, budgetId);
        if (active && !budget.isActive() && budget.getPeriod() != BudgetPeriod.CUSTOM) {
            requireNoActiveDuplicate(userId, budget.getScope(), budget.getCategoryId(), budget.getPeriod(),
                    budget.getCurrency(), budgetId);
        }
        budget.setActive(active);
        repository.flush();
        return get(userId, budgetId, null);
    }

    /** Budgets are plans, not financial records, so they can be deleted outright. */
    @Transactional
    public void delete(UUID userId, UUID budgetId) {
        repository.delete(load(userId, budgetId));
        log.info("Budget deleted budgetId={}", budgetId);
    }

    // ---- period maths ----

    /** First day of the MONTHLY or WEEKLY period containing {@code date}. */
    static LocalDate periodStart(BudgetPeriod period, LocalDate date, DayOfWeek weekStart) {
        return switch (period) {
            case MONTHLY -> date.withDayOfMonth(1);
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(weekStart));
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM budgets have explicit dates");
        };
    }

    static LocalDate periodEnd(BudgetPeriod period, LocalDate start) {
        return switch (period) {
            case MONTHLY -> start.with(TemporalAdjusters.lastDayOfMonth());
            case WEEKLY -> start.plusDays(6);
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM budgets have explicit dates");
        };
    }

    // ---- internals ----

    private List<BudgetResponse> toResponses(UUID userId, List<Budget> budgets, LocalDate day) {
        if (budgets.isEmpty()) {
            return List.of();
        }
        DayOfWeek weekStart = weekStart(userId);
        Set<UUID> categoryIds = budgets.stream().map(Budget::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, CategoryRef> refs = categories.refs(userId, categoryIds);
        return budgets.stream().map(b -> {
            CategoryRef category = b.getCategoryId() == null ? null : refs.get(b.getCategoryId());
            return new BudgetResponse(b.getId(), b.getName(), b.getScope(),
                    category == null ? null : new BudgetResponse.CategoryInfo(category.id(), category.name()),
                    b.getPeriod(), MoneyRules.scaled(b.getAmount(), b.getCurrency()), b.getCurrency(),
                    b.getStartDate(), b.getEndDate(), b.getAlertThresholds(), b.isActive(),
                    utilization(userId, b, day, weekStart), b.getCreatedAt(), b.getUpdatedAt());
        }).toList();
    }

    private Utilization utilization(UUID userId, Budget budget, LocalDate day, DayOfWeek weekStart) {
        if (!budget.isActive() || !budget.inEffectOn(day)) {
            return new Utilization(null, null, null, null, null, Status.NOT_IN_EFFECT, List.of(), null, null);
        }
        LocalDate start;
        LocalDate end;
        if (budget.getPeriod() == BudgetPeriod.CUSTOM) {
            start = budget.getStartDate();
            end = budget.getEndDate();
        } else {
            start = periodStart(budget.getPeriod(), day, weekStart);
            end = periodEnd(budget.getPeriod(), start);
        }
        Set<UUID> categoryIds = budget.getScope() == BudgetScope.CATEGORY
                ? categories.withSubcategories(userId, budget.getCategoryId()) : null;
        BigDecimal spent = totals.expenseTotal(userId, budget.getCurrency(), start, end, categoryIds);
        return utilizationOf(budget, spent, day, start, end);
    }

    static Utilization utilizationOf(Budget budget, BigDecimal spent, LocalDate day, LocalDate start, LocalDate end) {
        String currency = budget.getCurrency();
        BigDecimal amount = budget.getAmount();
        BigDecimal remaining = amount.subtract(spent);
        BigDecimal percent = spent.multiply(BigDecimal.valueOf(100)).divide(amount, 2, RoundingMode.HALF_UP);
        List<Integer> crossed = budget.getAlertThresholds().stream()
                .filter(t -> percent.compareTo(BigDecimal.valueOf(t)) >= 0)
                .toList();
        Status status = spent.compareTo(amount) > 0 ? Status.EXCEEDED
                : crossed.isEmpty() ? Status.ON_TRACK : Status.ALERT;
        long daysLeft = day.isAfter(end) ? 0 : ChronoUnit.DAYS.between(day.isBefore(start) ? start : day, end) + 1;
        BigDecimal allowance = daysLeft == 0 ? BigDecimal.ZERO
                : remaining.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(daysLeft), MoneyRules.fractionDigits(currency), RoundingMode.DOWN);
        return new Utilization(start, end, MoneyRules.scaled(spent, currency), MoneyRules.scaled(remaining, currency),
                percent, status, crossed, daysLeft, MoneyRules.scaled(allowance, currency));
    }

    private void requireNoActiveDuplicate(UUID userId, BudgetScope scope, UUID categoryId, BudgetPeriod period,
                                          String currency, UUID excludeId) {
        boolean exists = repository.findByUserIdOrderByCreatedAt(userId).stream()
                .anyMatch(b -> b.isActive() && !b.getId().equals(excludeId) && b.getScope() == scope
                        && Objects.equals(b.getCategoryId(), categoryId) && b.getPeriod() == period
                        && b.getCurrency().equals(currency));
        if (exists) {
            throw new ApiException(ErrorCode.DUPLICATE_BUDGET,
                    "You already have an active " + period.name().toLowerCase() + " budget for this in " + currency);
        }
    }

    private static List<Integer> validThresholds(List<Integer> requested) {
        if (requested == null) {
            return DEFAULT_THRESHOLDS;
        }
        List<Integer> sorted = requested.stream().sorted().distinct().toList();
        if (sorted.size() != requested.size() || sorted.stream().anyMatch(t -> t == null || t < 1 || t > 200)) {
            throw new FieldValidationException("alertThresholds", "must be distinct percentages between 1 and 200");
        }
        return sorted;
    }

    private static String defaultName(CategoryRef category, BudgetPeriod period) {
        String label = switch (period) {
            case MONTHLY -> "monthly";
            case WEEKLY -> "weekly";
            case CUSTOM -> "custom";
        };
        return category == null
                ? Character.toUpperCase(label.charAt(0)) + label.substring(1) + " budget"
                : category.name() + " (" + label + ")";
    }

    private DayOfWeek weekStart(UUID userId) {
        return preferences.get(userId).weekStartDay();
    }

    private Budget load(UUID userId, UUID budgetId) {
        return repository.findByIdAndUserId(budgetId, userId).orElseThrow(() -> new ResourceNotFoundException("Budget"));
    }
}
