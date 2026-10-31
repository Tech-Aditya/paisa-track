package com.paisatrack.modules.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountRef;
import com.paisatrack.modules.account.AccountService;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.platform.api.PageResponse;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.modules.income.IncomeSourceRef;
import com.paisatrack.modules.income.IncomeSourceService;
import com.paisatrack.modules.transaction.TransactionSqlQueries.CategoryAmount;
import com.paisatrack.modules.transaction.TransactionSqlQueries.TopExpense;
import com.paisatrack.modules.transaction.TransactionSqlQueries.TypeTotal;
import com.paisatrack.modules.transaction.dto.DailySummaryResponse;
import com.paisatrack.modules.transaction.dto.DailySummaryResponse.CategoryTotal;
import com.paisatrack.modules.transaction.dto.DailySummaryResponse.CurrencySummary;
import com.paisatrack.modules.transaction.dto.DailySummaryResponse.HighestExpense;
import com.paisatrack.modules.transaction.dto.TransactionFilter;
import com.paisatrack.modules.transaction.dto.TransactionResponse;
import com.paisatrack.modules.transaction.dto.TransactionResponse.AccountInfo;
import com.paisatrack.modules.transaction.dto.TransactionResponse.CategoryInfo;
import com.paisatrack.modules.transaction.dto.TransactionResponse.SourceInfo;
import com.paisatrack.modules.user.UserPreferencesService;

/** Read side: lookups, filtered lists, history and daily summaries. */
@Service
public class TransactionQueryService {

    static final String SUMMARY_BASIS =
            "Active income and expense transactions only. Transfers between your accounts and voided entries are excluded.";
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TransactionRepository repository;
    private final TransactionSqlQueries sql;
    private final AccountService accounts;
    private final CategoryService categories;
    private final IncomeSourceService incomeSources;
    private final UserPreferencesService preferences;
    private final AuditService audit;

    TransactionQueryService(TransactionRepository repository, TransactionSqlQueries sql, AccountService accounts,
                            CategoryService categories, IncomeSourceService incomeSources,
                            UserPreferencesService preferences, AuditService audit) {
        this.repository = repository;
        this.sql = sql;
        this.accounts = accounts;
        this.categories = categories;
        this.incomeSources = incomeSources;
        this.preferences = preferences;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID userId, UUID transactionId) {
        return toResponses(userId, List.of(load(userId, transactionId))).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(UUID userId, TransactionFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new FieldValidationException("from", "must not be after 'to'");
        }
        if (filter.accountId() != null) {
            accounts.require(userId, filter.accountId());
        }
        Set<UUID> categoryIds = filter.categoryId() == null ? null
                : categories.withSubcategories(userId, filter.categoryId());

        int page = filter.page() == null ? 0 : filter.page();
        int size = filter.size() == null ? DEFAULT_PAGE_SIZE : filter.size();
        Page<Transaction> result = repository.findAll(specification(userId, filter, categoryIds),
                PageRequest.of(page, size, sort(filter)));
        return PageResponse.of(result, toResponses(userId, result.getContent()));
    }

    /** Everything that moved money in or out of one account. */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> listForAccount(UUID userId, UUID accountId, TransactionFilter filter) {
        TransactionFilter scoped = new TransactionFilter(filter.type(), filter.from(), filter.to(), accountId,
                filter.categoryId(), filter.paymentMethod(), filter.incomeSourceId(), filter.q(), filter.minAmount(), filter.maxAmount(),
                filter.includeVoided(), filter.page(), filter.size(), filter.sortBy(), filter.direction());
        return list(userId, scoped);
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(UUID userId, UUID transactionId) {
        load(userId, transactionId);
        return audit.history(userId, TransactionService.AUDIT_ENTITY, transactionId);
    }

    /** Defaults to today in the user's timezone. */
    @Transactional(readOnly = true)
    public DailySummaryResponse dailySummary(UUID userId, LocalDate date) {
        LocalDate day = date != null ? date : preferences.today(userId);
        List<TypeTotal> totals = sql.totals(userId, day, day);
        List<TopExpense> highest = sql.highestExpenses(userId, day, day);
        List<CategoryAmount> byCategory = sql.expenseByCategory(userId, day, day);

        Set<UUID> categoryIds = new HashSet<>();
        highest.forEach(h -> categoryIds.add(h.categoryId()));
        byCategory.forEach(c -> categoryIds.add(c.categoryId()));
        Map<UUID, CategoryRef> refs = categories.refs(userId, categoryIds);

        Set<String> currencies = new TreeSet<>();
        totals.forEach(t -> currencies.add(t.currency()));
        if (currencies.isEmpty()) {
            currencies.add(preferences.defaultCurrency(userId));
        }
        List<CurrencySummary> summaries = currencies.stream()
                .map(currency -> currencySummary(currency, totals, highest, byCategory, refs))
                .toList();
        return new DailySummaryResponse(day, SUMMARY_BASIS, summaries);
    }

    Transaction load(UUID userId, UUID transactionId) {
        return repository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction"));
    }

    // ---- internals ----

    private static CurrencySummary currencySummary(String currency, List<TypeTotal> totals, List<TopExpense> highest,
                                                   List<CategoryAmount> byCategory, Map<UUID, CategoryRef> refs) {
        TypeTotal income = find(totals, currency, TransactionType.INCOME);
        TypeTotal expense = find(totals, currency, TransactionType.EXPENSE);
        BigDecimal totalIncome = income == null ? BigDecimal.ZERO : income.total();
        BigDecimal totalExpense = expense == null ? BigDecimal.ZERO : expense.total();
        long expenseCount = expense == null ? 0 : expense.count();
        BigDecimal average = expenseCount == 0 ? BigDecimal.ZERO
                : totalExpense.divide(BigDecimal.valueOf(expenseCount), MoneyRules.fractionDigits(currency), RoundingMode.HALF_EVEN);

        HighestExpense top = highest.stream().filter(h -> h.currency().equals(currency)).findFirst()
                .map(h -> new HighestExpense(h.id(), MoneyRules.scaled(h.amount(), currency), h.description(),
                        nameOf(refs.get(h.categoryId()))))
                .orElse(null);
        List<CategoryTotal> categoryTotals = byCategory.stream()
                .filter(c -> c.currency().equals(currency))
                .map(c -> new CategoryTotal(c.categoryId(), nameOf(refs.get(c.categoryId())),
                        MoneyRules.scaled(c.total(), currency), c.count()))
                .toList();

        return new CurrencySummary(currency,
                MoneyRules.scaled(totalIncome, currency),
                MoneyRules.scaled(totalExpense, currency),
                MoneyRules.scaled(totalIncome.subtract(totalExpense), currency),
                income == null ? 0 : income.count(),
                expenseCount,
                MoneyRules.scaled(average, currency),
                top,
                categoryTotals);
    }

    private static TypeTotal find(List<TypeTotal> totals, String currency, TransactionType type) {
        return totals.stream().filter(t -> t.currency().equals(currency) && t.type() == type).findFirst().orElse(null);
    }

    private static String nameOf(CategoryRef ref) {
        if (ref == null) {
            return null;
        }
        return ref.parentName() == null ? ref.name() : ref.parentName() + " / " + ref.name();
    }

    private static Specification<Transaction> specification(UUID userId, TransactionFilter f, Set<UUID> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("userId"), userId));
            if (!Boolean.TRUE.equals(f.includeVoided())) {
                p.add(cb.equal(root.get("status"), TransactionStatus.ACTIVE));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("transactionDate"), f.to()));
            }
            if (f.accountId() != null) {
                p.add(cb.or(cb.equal(root.get("accountId"), f.accountId()), cb.equal(root.get("toAccountId"), f.accountId())));
            }
            if (categoryIds != null) {
                p.add(root.get("categoryId").in(categoryIds));
            }
            if (f.incomeSourceId() != null) {
                p.add(cb.equal(root.get("incomeSourceId"), f.incomeSourceId()));
            }
            if (f.paymentMethod() != null) {
                p.add(cb.equal(root.get("paymentMethod"), f.paymentMethod()));
            }
            if (f.minAmount() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount()));
            }
            if (f.maxAmount() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("amount"), f.maxAmount()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String pattern = "%" + escapeLike(f.q().strip().toLowerCase()) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("description")), pattern, '\\'),
                        cb.like(cb.lower(root.get("counterparty")), pattern, '\\')));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static Sort sort(TransactionFilter f) {
        Sort.Direction direction = f.direction() == TransactionFilter.SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
        TransactionFilter.SortField field = f.sortBy() == null ? TransactionFilter.SortField.DATE : f.sortBy();
        // createdAt breaks ties so paging is stable.
        return switch (field) {
            case DATE -> Sort.by(direction, "transactionDate").and(Sort.by(direction, "createdAt"));
            case AMOUNT -> Sort.by(direction, "amount").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case CREATED_AT -> Sort.by(direction, "createdAt");
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Batch-resolves account and category names: two queries per page, no N+1. */
    private List<TransactionResponse> toResponses(UUID userId, List<Transaction> transactions) {
        Set<UUID> accountIds = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        Set<UUID> sourceIds = new HashSet<>();
        for (Transaction t : transactions) {
            if (t.getIncomeSourceId() != null) {
                sourceIds.add(t.getIncomeSourceId());
            }
            Stream.of(t.getAccountId(), t.getToAccountId()).filter(Objects::nonNull).forEach(accountIds::add);
            if (t.getCategoryId() != null) {
                categoryIds.add(t.getCategoryId());
            }
        }
        Map<UUID, AccountRef> accountRefs = accounts.refs(userId, accountIds);
        Map<UUID, CategoryRef> categoryRefs = categories.refs(userId, categoryIds);
        Map<UUID, IncomeSourceRef> sourceRefs = incomeSources.refs(userId, sourceIds);
        return transactions.stream().map(t -> new TransactionResponse(
                t.getId(), t.getType(), t.getStatus(),
                MoneyRules.scaled(t.getAmount(), t.getCurrency()), t.getCurrency(),
                t.getTransactionDate(), t.getTransactionTime(),
                accountInfo(accountRefs, t.getAccountId()),
                accountInfo(accountRefs, t.getToAccountId()),
                categoryInfo(categoryRefs, t.getCategoryId()),
                t.getDescription(), t.getCounterparty(), t.getPaymentMethod(), t.getNotes(),
                sourceInfo(sourceRefs, t.getIncomeSourceId()),
                t.getIncomePeriod() == null ? null : YearMonth.from(t.getIncomePeriod()),
                t.getVoidedAt(), t.getVoidReason(), t.getCreatedAt(), t.getUpdatedAt())).toList();
    }

    private static AccountInfo accountInfo(Map<UUID, AccountRef> refs, UUID id) {
        AccountRef ref = id == null ? null : refs.get(id);
        return ref == null ? null : new AccountInfo(ref.id(), ref.name());
    }

    private static SourceInfo sourceInfo(Map<UUID, IncomeSourceRef> refs, UUID id) {
        IncomeSourceRef ref = id == null ? null : refs.get(id);
        return ref == null ? null : new SourceInfo(ref.id(), ref.name());
    }

    private static CategoryInfo categoryInfo(Map<UUID, CategoryRef> refs, UUID id) {
        CategoryRef ref = id == null ? null : refs.get(id);
        return ref == null ? null : new CategoryInfo(ref.id(), ref.name(), ref.parentId(), ref.parentName());
    }
}
