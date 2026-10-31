package com.paisatrack.modules.account;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.dto.AccountResponse;
import com.paisatrack.modules.account.dto.AccountsSummaryResponse;
import com.paisatrack.modules.account.dto.AccountsSummaryResponse.CurrencyTotals;
import com.paisatrack.modules.account.dto.CreateAccountRequest;
import com.paisatrack.modules.account.dto.UpdateAccountRequest;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.modules.user.UserRegisteredEvent;

/**
 * Accounts and their balances. Balances are derived on every read (opening balance + active
 * transactions), so they can't drift from the transaction history.
 */
@Service
public class AccountService {

    static final String AUDIT_ENTITY = "ACCOUNT";
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final AccountFlowProvider flows;
    private final List<LiabilityProvider> liabilities;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final Clock clock;

    AccountService(AccountRepository repository, AccountFlowProvider flows, List<LiabilityProvider> liabilities,
                   UserPreferencesService preferences,
                   AuditService audit, Clock clock) {
        this.repository = repository;
        this.flows = flows;
        this.liabilities = liabilities;
        this.preferences = preferences;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID userId, boolean includeInactive) {
        Map<UUID, BigDecimal> netFlows = flows.netFlows(userId);
        return repository.findByUserIdOrderByCreatedAt(userId).stream()
                .filter(a -> includeInactive || a.isActive())
                .map(a -> toResponse(a, netFlows))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID userId, UUID accountId) {
        return toResponse(load(userId, accountId), flows.netFlows(userId));
    }

    /**
     * Per-currency totals over all accounts (deactivated ones included, they may still hold money),
     * minus outstanding loans. Net worth = assets - card balances owed - loan principal outstanding.
     */
    @Transactional(readOnly = true)
    public AccountsSummaryResponse summary(UUID userId) {
        Map<String, List<AccountResponse>> byCurrency = list(userId, true).stream()
                .collect(Collectors.groupingBy(AccountResponse::currency, TreeMap::new, Collectors.toList()));
        Map<String, BigDecimal> loans = new TreeMap<>();
        liabilities.forEach(p -> p.outstandingByCurrency(userId).forEach((c, v) -> loans.merge(c, v, BigDecimal::add)));
        loans.keySet().forEach(c -> byCurrency.putIfAbsent(c, List.of()));
        List<CurrencyTotals> totals = byCurrency.entrySet().stream().map(entry -> {
            String currency = entry.getKey();
            BigDecimal assets = sum(entry.getValue(), AccountNature.ASSET);
            BigDecimal owed = sum(entry.getValue(), AccountNature.LIABILITY);
            BigDecimal loan = loans.getOrDefault(currency, BigDecimal.ZERO);
            return new CurrencyTotals(currency, entry.getValue().size(),
                    MoneyRules.scaled(assets, currency),
                    MoneyRules.scaled(owed, currency),
                    MoneyRules.scaled(loan, currency),
                    MoneyRules.scaled(assets.subtract(owed).subtract(loan), currency));
        }).toList();
        return new AccountsSummaryResponse(totals);
    }

    @Transactional
    public AccountResponse create(UUID userId, CreateAccountRequest request) {
        String currency = request.currency() != null ? request.currency() : preferences.defaultCurrency(userId);
        BigDecimal opening = request.openingBalance() != null ? request.openingBalance() : BigDecimal.ZERO;
        MoneyRules.requireValidMagnitude("openingBalance", opening, currency);
        requireUniqueName(userId, request.name(), null);

        Account account = new Account(userId, request.name(), request.type(), currency, opening);
        applyCreditLimit(account, request.creditLimit());
        account.setInstitution(blankToNull(request.institution()));
        account.setAccountLast4(blankToNull(request.accountLast4()));
        account.setNotes(blankToNull(request.notes()));
        if (repository.findByUserIdAndDefaultAccountTrue(userId).isEmpty()) {
            account.markDefault();
        }
        repository.saveAndFlush(account);
        audit.record(userId, AUDIT_ENTITY, account.getId(), AuditAction.CREATED, AuditService.diff(Map.of(), account.snapshot()));
        log.info("Account created accountId={} type={}", account.getId(), account.getType());
        return toResponse(account, Map.of());
    }

    @Transactional
    public AccountResponse update(UUID userId, UUID accountId, UpdateAccountRequest request) {
        Account account = load(userId, accountId);
        Map<String, Object> before = account.snapshot();
        if (request.name() != null) {
            requireUniqueName(userId, request.name(), accountId);
            account.rename(request.name());
        }
        if (request.openingBalance() != null) {
            MoneyRules.requireValidMagnitude("openingBalance", request.openingBalance(), account.getCurrency());
            account.setOpeningBalance(request.openingBalance());
        }
        if (request.creditLimit() != null) {
            applyCreditLimit(account, request.creditLimit());
        }
        if (request.institution() != null) {
            account.setInstitution(blankToNull(request.institution()));
        }
        if (request.accountLast4() != null) {
            account.setAccountLast4(blankToNull(request.accountLast4()));
        }
        if (request.notes() != null) {
            account.setNotes(blankToNull(request.notes()));
        }
        repository.flush();
        Map<String, Object> changes = AuditService.diff(before, account.snapshot());
        if (!changes.isEmpty()) {
            audit.record(userId, AUDIT_ENTITY, accountId, AuditAction.UPDATED, changes);
        }
        return get(userId, accountId);
    }

    /** History and balance are kept; the account just can't take new transactions. */
    @Transactional
    public AccountResponse deactivate(UUID userId, UUID accountId) {
        Account account = load(userId, accountId);
        if (account.isDefaultAccount()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "This is your default account. Make another account the default first.");
        }
        if (account.isActive()) {
            account.deactivate(clock.instant());
            repository.flush();
            audit.record(userId, AUDIT_ENTITY, accountId, AuditAction.DEACTIVATED, null);
        }
        return get(userId, accountId);
    }

    @Transactional
    public AccountResponse activate(UUID userId, UUID accountId) {
        Account account = load(userId, accountId);
        if (!account.isActive()) {
            account.activate();
            repository.flush();
            audit.record(userId, AUDIT_ENTITY, accountId, AuditAction.ACTIVATED, null);
        }
        return get(userId, accountId);
    }

    /** The default account is used when a new expense or income names no account. */
    @Transactional
    public AccountResponse makeDefault(UUID userId, UUID accountId) {
        Account account = load(userId, accountId);
        if (!account.isActive()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "A deactivated account can't be the default");
        }
        if (!account.isDefaultAccount()) {
            repository.clearDefault(userId);
            Account reloaded = load(userId, accountId);
            reloaded.markDefault();
            repository.flush();
        }
        return get(userId, accountId);
    }

    // ---- API for other modules ----

    /** Owned account (404 otherwise), active or not. */
    @Transactional(readOnly = true)
    public AccountRef require(UUID userId, UUID accountId) {
        return AccountRef.of(load(userId, accountId));
    }

    /**
     * Account to post a transaction to: the given one, or the user's default when null.
     * Must be active, unless it is {@code currentAccountId} (an edit that keeps its account).
     */
    @Transactional(readOnly = true)
    public AccountRef requireForTransaction(UUID userId, UUID accountId, String field, UUID currentAccountId) {
        Account account;
        if (accountId == null) {
            account = repository.findByUserIdAndDefaultAccountTrue(userId)
                    .orElseThrow(() -> new FieldValidationException(field, "is required (you have no default account)"));
        } else {
            account = load(userId, accountId);
        }
        if (!account.isActive() && !account.getId().equals(currentAccountId)) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Account '" + account.getName() + "' is deactivated");
        }
        return AccountRef.of(account);
    }

    @Transactional(readOnly = true)
    public Map<UUID, AccountRef> refs(UUID userId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByUserIdAndIdIn(userId, accountIds).stream()
                .collect(Collectors.toMap(Account::getId, AccountRef::of, (a, b) -> a));
    }

    /** Every user starts with a default Cash account in their preferred currency. */
    @EventListener
    void onUserRegistered(UserRegisteredEvent event) {
        Account cash = new Account(event.userId(), "Cash", AccountType.CASH, event.defaultCurrency(), BigDecimal.ZERO);
        cash.markDefault();
        repository.save(cash);
    }

    // ---- internals ----

    private Account load(UUID userId, UUID accountId) {
        return repository.findByIdAndUserId(accountId, userId).orElseThrow(() -> new ResourceNotFoundException("Account"));
    }

    private void requireUniqueName(UUID userId, String name, UUID excludeId) {
        String trimmed = name.strip();
        boolean taken = excludeId == null
                ? repository.existsByUserIdAndNameIgnoreCase(userId, trimmed)
                : repository.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, trimmed, excludeId);
        if (taken) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "You already have an account named '" + trimmed + "'");
        }
    }

    private static void applyCreditLimit(Account account, BigDecimal creditLimit) {
        if (creditLimit == null) {
            return;
        }
        if (account.getType() != AccountType.CREDIT_CARD) {
            throw new FieldValidationException("creditLimit", "applies to credit cards only");
        }
        MoneyRules.requireValidAmount("creditLimit", creditLimit, account.getCurrency());
        account.setCreditLimit(creditLimit);
    }

    private static AccountResponse toResponse(Account account, Map<UUID, BigDecimal> netFlows) {
        String currency = account.getCurrency();
        BigDecimal net = netFlows.getOrDefault(account.getId(), BigDecimal.ZERO);
        // Liabilities track money owed: spending on the card (outflow) raises it, payments (inflow) lower it.
        BigDecimal balance = account.getType().nature() == AccountNature.ASSET
                ? account.getOpeningBalance().add(net)
                : account.getOpeningBalance().subtract(net);
        BigDecimal availableCredit = account.getCreditLimit() == null ? null : account.getCreditLimit().subtract(balance);
        return new AccountResponse(
                account.getId(), account.getName(), account.getType(), account.getType().nature(), currency,
                MoneyRules.scaled(account.getOpeningBalance(), currency),
                MoneyRules.scaled(balance, currency),
                MoneyRules.scaled(account.getCreditLimit(), currency),
                MoneyRules.scaled(availableCredit, currency),
                account.getInstitution(), account.getAccountLast4(), account.getNotes(),
                account.isDefaultAccount(), account.isActive(), account.getCreatedAt(), account.getUpdatedAt());
    }

    private static BigDecimal sum(List<AccountResponse> accounts, AccountNature nature) {
        return accounts.stream().filter(a -> a.nature() == nature)
                .map(AccountResponse::currentBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
