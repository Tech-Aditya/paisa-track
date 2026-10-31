package com.paisatrack.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.account.AccountRef;
import com.paisatrack.account.AccountService;
import com.paisatrack.audit.AuditAction;
import com.paisatrack.audit.AuditService;
import com.paisatrack.category.CategoryRef;
import com.paisatrack.category.CategoryService;
import com.paisatrack.category.CategoryType;
import com.paisatrack.common.money.MoneyRules;
import com.paisatrack.exception.ApiException;
import com.paisatrack.exception.ErrorCode;
import com.paisatrack.exception.FieldValidationException;
import com.paisatrack.exception.ResourceNotFoundException;
import com.paisatrack.idempotency.IdempotencyService;
import com.paisatrack.transaction.dto.ExpenseRequest;
import com.paisatrack.transaction.dto.IncomeRequest;
import com.paisatrack.transaction.dto.TransactionResponse;
import com.paisatrack.transaction.dto.TransferRequest;
import com.paisatrack.user.UserPreferencesService;

/**
 * Write side: create, edit, void and restore. Every change is audited in the same transaction.
 * Balances need no updating here because they are derived from transactions on read.
 */
@Service
public class TransactionService {

    static final String AUDIT_ENTITY = "TRANSACTION";
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repository;
    private final TransactionQueryService queries;
    private final AccountService accounts;
    private final CategoryService categories;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final Clock clock;

    TransactionService(TransactionRepository repository, TransactionQueryService queries, AccountService accounts,
                       CategoryService categories, UserPreferencesService preferences, AuditService audit,
                       IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.queries = queries;
        this.accounts = accounts;
        this.categories = categories;
        this.preferences = preferences;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public TransactionResponse createExpense(UUID userId, ExpenseRequest request, String idempotencyKey) {
        return create(userId, TransactionType.EXPENSE, request, idempotencyKey, () -> resolveEntry(userId,
                TransactionType.EXPENSE, Entry.of(request), null));
    }

    @Transactional
    public TransactionResponse updateExpense(UUID userId, UUID transactionId, ExpenseRequest request) {
        return update(userId, transactionId, TransactionType.EXPENSE,
                current -> resolveEntry(userId, TransactionType.EXPENSE, Entry.of(request), current));
    }

    @Transactional
    public TransactionResponse createIncome(UUID userId, IncomeRequest request, String idempotencyKey) {
        return create(userId, TransactionType.INCOME, request, idempotencyKey, () -> resolveEntry(userId,
                TransactionType.INCOME, Entry.of(request), null));
    }

    @Transactional
    public TransactionResponse updateIncome(UUID userId, UUID transactionId, IncomeRequest request) {
        return update(userId, transactionId, TransactionType.INCOME,
                current -> resolveEntry(userId, TransactionType.INCOME, Entry.of(request), current));
    }

    @Transactional
    public TransactionResponse createTransfer(UUID userId, TransferRequest request, String idempotencyKey) {
        return create(userId, TransactionType.TRANSFER, request, idempotencyKey,
                () -> resolveTransfer(userId, request, null));
    }

    @Transactional
    public TransactionResponse updateTransfer(UUID userId, UUID transactionId, TransferRequest request) {
        return update(userId, transactionId, TransactionType.TRANSFER,
                current -> resolveTransfer(userId, request, current));
    }

    /** Removes the transaction from balances and totals but keeps it. Repeating it is harmless. */
    @Transactional
    public TransactionResponse voidTransaction(UUID userId, UUID transactionId, String reason) {
        Transaction transaction = queries.load(userId, transactionId);
        if (!transaction.isVoided()) {
            String cleanReason = clean(reason);
            transaction.markVoided(clock.instant(), cleanReason);
            repository.flush();
            audit.record(userId, AUDIT_ENTITY, transactionId, AuditAction.VOIDED,
                    cleanReason == null ? null : Map.of("reason", cleanReason));
            log.info("Transaction voided transactionId={}", transactionId);
        }
        return queries.get(userId, transactionId);
    }

    /** Undoes a void. Its accounts and category must still be active. Repeating it is harmless. */
    @Transactional
    public TransactionResponse restore(UUID userId, UUID transactionId) {
        Transaction transaction = queries.load(userId, transactionId);
        if (transaction.isVoided()) {
            accounts.requireForTransaction(userId, transaction.getAccountId(), "accountId", null);
            if (transaction.getToAccountId() != null) {
                accounts.requireForTransaction(userId, transaction.getToAccountId(), "toAccountId", null);
            }
            if (transaction.getCategoryId() != null) {
                categories.requireForTransaction(userId, transaction.getCategoryId(), categoryType(transaction.getType()), null);
            }
            transaction.restore();
            repository.flush();
            audit.record(userId, AUDIT_ENTITY, transactionId, AuditAction.RESTORED, null);
            log.info("Transaction restored transactionId={}", transactionId);
        }
        return queries.get(userId, transactionId);
    }

    // ---- create / update skeletons ----

    private TransactionResponse create(UUID userId, TransactionType type, Object request, String idempotencyKey,
                                       Supplier<TransactionDetails> resolve) {
        UUID id = idempotency.execute(userId, idempotencyKey, "CREATE_" + type, request, () -> {
            Transaction transaction = repository.saveAndFlush(new Transaction(userId, type, resolve.get()));
            audit.record(userId, AUDIT_ENTITY, transaction.getId(), AuditAction.CREATED,
                    AuditService.diff(Map.of(), transaction.snapshot()));
            log.info("Transaction created transactionId={} type={}", transaction.getId(), type);
            return transaction.getId();
        });
        return queries.get(userId, id);
    }

    private TransactionResponse update(UUID userId, UUID transactionId, TransactionType type,
                                       Function<Transaction, TransactionDetails> resolve) {
        Transaction transaction = queries.load(userId, transactionId);
        if (transaction.getType() != type) {
            throw new ResourceNotFoundException(type == TransactionType.TRANSFER ? "Transfer" : capitalized(type));
        }
        if (transaction.isVoided()) {
            throw new ApiException(ErrorCode.TRANSACTION_VOIDED, "This transaction is voided. Restore it before editing.");
        }
        Map<String, Object> before = transaction.snapshot();
        transaction.apply(resolve.apply(transaction));
        repository.flush();
        Map<String, Object> changes = AuditService.diff(before, transaction.snapshot());
        if (!changes.isEmpty()) {
            audit.record(userId, AUDIT_ENTITY, transactionId, AuditAction.UPDATED, changes);
            log.info("Transaction updated transactionId={} fields={}", transactionId, changes.keySet());
        }
        return queries.get(userId, transactionId);
    }

    // ---- validation and defaults ----

    /** Common shape of expense and income requests. */
    private record Entry(BigDecimal amount, UUID categoryId, UUID accountId, String currency, LocalDate date,
                         LocalTime time, String description, String counterparty, PaymentMethod paymentMethod,
                         String notes) {
        static Entry of(ExpenseRequest r) {
            return new Entry(r.amount(), r.categoryId(), r.accountId(), r.currency(), r.transactionDate(),
                    r.transactionTime(), r.description(), r.merchant(), r.paymentMethod(), r.notes());
        }

        static Entry of(IncomeRequest r) {
            return new Entry(r.amount(), r.categoryId(), r.accountId(), r.currency(), r.transactionDate(),
                    r.transactionTime(), r.description(), r.payer(), r.paymentMethod(), r.notes());
        }
    }

    private TransactionDetails resolveEntry(UUID userId, TransactionType type, Entry entry, Transaction current) {
        if (current != null && entry.accountId() == null) {
            throw new FieldValidationException("accountId", "is required when editing");
        }
        AccountRef account = accounts.requireForTransaction(userId, entry.accountId(), "accountId",
                current == null ? null : current.getAccountId());
        String currency = resolveCurrency(entry.currency(), account);
        MoneyRules.requireValidAmount("amount", entry.amount(), currency);
        CategoryRef category = categories.requireForTransaction(userId, entry.categoryId(), categoryType(type),
                current == null ? null : current.getCategoryId());
        PaymentMethod paymentMethod = entry.paymentMethod() != null
                ? entry.paymentMethod() : PaymentMethod.defaultFor(account.type());
        return new TransactionDetails(entry.amount(), currency, account.id(), null, category.id(),
                resolveDate(userId, entry.date(), current), entry.time(),
                clean(entry.description()), clean(entry.counterparty()), paymentMethod, clean(entry.notes()));
    }

    private TransactionDetails resolveTransfer(UUID userId, TransferRequest request, Transaction current) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Choose two different accounts for a transfer");
        }
        AccountRef from = accounts.requireForTransaction(userId, request.fromAccountId(), "fromAccountId",
                current == null ? null : current.getAccountId());
        AccountRef to = accounts.requireForTransaction(userId, request.toAccountId(), "toAccountId",
                current == null ? null : current.getToAccountId());
        if (!from.currency().equals(to.currency())) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                    "Both accounts must use the same currency (" + from.currency() + " vs " + to.currency() + ")");
        }
        String currency = resolveCurrency(request.currency(), from);
        MoneyRules.requireValidAmount("amount", request.amount(), currency);
        return new TransactionDetails(request.amount(), currency, from.id(), to.id(), null,
                resolveDate(userId, request.transactionDate(), current), request.transactionTime(),
                clean(request.description()), null, null, clean(request.notes()));
    }

    /** Amounts are stored in the account's currency. Conversion arrives with multi-currency support. */
    private static String resolveCurrency(String requested, AccountRef account) {
        if (requested != null && !requested.equals(account.currency())) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                    "Account '" + account.name() + "' uses " + account.currency() + ", not " + requested);
        }
        return account.currency();
    }

    /** Defaults to today (user's timezone) on create. Allows one day ahead for timezone slack, no more. */
    private LocalDate resolveDate(UUID userId, LocalDate requested, Transaction current) {
        if (requested == null && current != null) {
            throw new FieldValidationException("transactionDate", "is required when editing");
        }
        LocalDate today = preferences.today(userId);
        LocalDate date = requested != null ? requested : today;
        if (date.isAfter(today.plusDays(1))) {
            throw new FieldValidationException("transactionDate", "can't be in the future");
        }
        return date;
    }

    private static CategoryType categoryType(TransactionType type) {
        return type == TransactionType.INCOME ? CategoryType.INCOME : CategoryType.EXPENSE;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String capitalized(TransactionType type) {
        String name = type.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
