package com.paisatrack.modules.loan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountRef;
import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.category.CategoryType;
import com.paisatrack.modules.loan.dto.CreateLoanRequest;
import com.paisatrack.modules.loan.dto.EmiPaymentRequest;
import com.paisatrack.modules.loan.dto.InstallmentResponse;
import com.paisatrack.modules.loan.dto.LoanPaymentResponse;
import com.paisatrack.modules.loan.dto.LoanPreviewRequest;
import com.paisatrack.modules.loan.dto.LoanPreviewResponse;
import com.paisatrack.modules.loan.dto.LoanResponse;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.loan.dto.PrepaymentRequest;
import com.paisatrack.modules.loan.dto.UpdateLoanRequest;
import com.paisatrack.modules.transaction.TransactionService;
import com.paisatrack.modules.transaction.TransactionService.LoanMovement;
import com.paisatrack.modules.transaction.TransactionType;
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
 * Formal loans and EMIs. An EMI payment posts its interest as an EXPENSE and its principal as
 * LOAN_PRINCIPAL (never spending), so principal is never counted twice. Outstanding principal is
 * derived from payments. EMIs are paid in order and only the latest payment can be voided, which
 * keeps the stored schedule consistent. Every change to a loan runs under a row lock.
 */
@Service
public class LoanService {

    static final String AUDIT_ENTITY = "LOAN";
    static final String INTEREST_CATEGORY_CODE = "FINANCE_INTEREST";
    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loans;
    private final LoanInstallmentRepository installments;
    private final LoanPaymentRepository payments;
    private final TransactionService transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final UserPreferencesService preferences;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final Clock clock;

    LoanService(LoanRepository loans, LoanInstallmentRepository installments, LoanPaymentRepository payments,
                TransactionService transactions, AccountService accounts, CategoryService categories,
                UserPreferencesService preferences, AuditService audit, IdempotencyService idempotency, Clock clock) {
        this.loans = loans;
        this.installments = installments;
        this.payments = payments;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.preferences = preferences;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---- queries ----

    /** EMI calculator: same maths as a real loan, nothing saved. */
    @Transactional(readOnly = true)
    public LoanPreviewResponse preview(UUID userId, LoanPreviewRequest r) {
        String currency = r.currency() != null ? r.currency() : preferences.defaultCurrency(userId);
        MoneyRules.requireValidAmount("principal", r.principal(), currency);
        int scale = MoneyRules.fractionDigits(currency);
        BigDecimal emi = r.emiAmount() != null ? validEmi(r.emiAmount(), currency)
                : Amortization.emi(r.principal(), r.annualRate(), r.tenureMonths(), scale);
        LocalDate first = r.firstEmiDate() != null ? r.firstEmiDate() : preferences.today(userId).plusMonths(1);
        List<Amortization.Row> rows = Amortization.schedule(r.principal(), r.annualRate(), emi, first, 1, r.tenureMonths(), scale);
        BigDecimal interest = rows.stream().map(Amortization.Row::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<InstallmentResponse> schedule = rows.stream().map(row -> new InstallmentResponse(row.number(), row.dueDate(),
                MoneyRules.scaled(row.emi(), currency), MoneyRules.scaled(row.principal(), currency),
                MoneyRules.scaled(row.interest(), currency), MoneyRules.scaled(row.opening(), currency),
                MoneyRules.scaled(row.closing(), currency), InstallmentResponse.Status.UPCOMING, null, null)).toList();
        return new LoanPreviewResponse(MoneyRules.scaled(emi, currency), rows.size(), MoneyRules.scaled(interest, currency),
                MoneyRules.scaled(r.principal().add(interest), currency), schedule);
    }

    @Transactional(readOnly = true)
    public List<LoanResponse> list(UUID userId, boolean includeClosed) {
        return toResponses(userId, loans.findAllActive(userId), false).stream()
                .filter(l -> includeClosed || l.status() == LoanResponse.Status.ACTIVE)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanResponse get(UUID userId, UUID loanId) {
        return toResponses(userId, List.of(load(userId, loanId)), true).getFirst();
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> schedule(UUID userId, UUID loanId) {
        Loan loan = load(userId, loanId);
        State s = state(loan, installments.findByLoanIdOrderByNumberAsc(loanId), activePayments(loanId), preferences.today(userId));
        return s.installments();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(UUID userId, UUID loanId) {
        load(userId, loanId);
        return audit.history(userId, AUDIT_ENTITY, loanId);
    }

    /** Per-currency totals for active loans, and EMIs missed or due within {@code days}. */
    @Transactional(readOnly = true)
    public LoanSummaryResponse summary(UUID userId, int days) {
        LocalDate today = preferences.today(userId);
        List<Loan> all = loans.findAllActive(userId);
        Map<UUID, State> states = states(all, today);
        Map<String, List<Loan>> byCurrency = all.stream()
                .collect(Collectors.groupingBy(Loan::getCurrency, TreeMap::new, Collectors.toList()));
        List<LoanSummaryResponse.CurrencyTotals> totals = byCurrency.entrySet().stream().map(e -> {
            String c = e.getKey();
            List<Loan> open = e.getValue().stream().filter(l -> states.get(l.getId()).outstanding().signum() > 0).toList();
            List<LoanPayment> thisYear = e.getValue().stream().flatMap(l -> states.get(l.getId()).payments().stream())
                    .filter(p -> p.getPaymentDate().getYear() == today.getYear()).toList();
            return new LoanSummaryResponse.CurrencyTotals(c, open.size(),
                    MoneyRules.scaled(open.stream().map(l -> states.get(l.getId()).outstanding()).reduce(BigDecimal.ZERO, BigDecimal::add), c),
                    MoneyRules.scaled(open.stream().map(Loan::getEmiAmount).reduce(BigDecimal.ZERO, BigDecimal::add), c),
                    MoneyRules.scaled(thisYear.stream().map(LoanPayment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add), c),
                    MoneyRules.scaled(thisYear.stream().map(LoanPayment::getInterestAmount).reduce(BigDecimal.ZERO, BigDecimal::add), c),
                    open.stream().mapToInt(l -> states.get(l.getId()).missedCount()).sum());
        }).toList();
        return new LoanSummaryResponse(totals, upcoming(all, states, today, days));
    }

    @Transactional(readOnly = true)
    public List<LoanSummaryResponse.UpcomingEmi> upcoming(UUID userId, int days) {
        LocalDate today = preferences.today(userId);
        List<Loan> all = loans.findAllActive(userId);
        return upcoming(all, states(all, today), today, days);
    }

    // ---- commands ----

    @Transactional
    public LoanResponse create(UUID userId, CreateLoanRequest r, String idempotencyKey) {
        UUID id = idempotency.execute(userId, idempotencyKey, "CREATE_LOAN", r, () -> {
            LocalDate today = preferences.today(userId);
            LocalDate start = r.startDate() != null ? r.startDate() : today;
            requireNotFuture("startDate", start, today);
            LocalDate firstEmi = r.firstEmiDate() != null ? r.firstEmiDate() : start.plusMonths(1);
            if (firstEmi.isBefore(start)) {
                throw new FieldValidationException("firstEmiDate", "can't be before the disbursement date");
            }
            AccountRef disbursement = r.disbursementAccountId() == null ? null
                    : accounts.requireForTransaction(userId, r.disbursementAccountId(), "disbursementAccountId", null);
            String currency = disbursement != null ? disbursement.currency()
                    : r.currency() != null ? r.currency() : preferences.defaultCurrency(userId);
            if (r.currency() != null && !r.currency().equals(currency)) {
                throw new ApiException(ErrorCode.CURRENCY_MISMATCH, "The disbursement account uses " + currency + ", not " + r.currency());
            }
            MoneyRules.requireValidAmount("principal", r.principal(), currency);
            requireRateScale(r.annualRate());
            int scale = MoneyRules.fractionDigits(currency);
            BigDecimal emi = r.emiAmount() != null ? validEmi(r.emiAmount(), currency)
                    : Amortization.emi(r.principal(), r.annualRate(), r.tenureMonths(), scale);
            List<Amortization.Row> rows = Amortization.schedule(r.principal(), r.annualRate(), emi, firstEmi, 1, r.tenureMonths(), scale);

            Loan loan = new Loan(userId, r.name(), r.type(), currency);
            loan.setTerms(r.principal(), r.annualRate(), r.tenureMonths(), emi, start, firstEmi);
            loan.setLender(clean(r.lender()));
            loan.setNotes(clean(r.notes()));
            loan.setPaymentAccountId(r.paymentAccountId() == null ? null
                    : sameCurrency(accounts.requireForTransaction(userId, r.paymentAccountId(), "paymentAccountId", null), currency).id());
            loan.setInterestCategoryId(interestCategory(userId, r.interestCategoryId(), null));
            loans.saveAndFlush(loan);
            installments.saveAll(rows.stream().map(row -> new LoanInstallment(loan.getId(), row)).toList());

            if (disbursement != null) {
                UUID tx = transactions.recordLoanMovement(userId, new LoanMovement(TransactionType.LOAN_DISBURSEMENT, loan.getId(),
                        disbursement.id(), currency, r.principal(), start, null, "Loan disbursed · " + loan.getName(), loan.getLender()));
                loan.setDisbursement(disbursement.id(), tx);
            }
            int alreadyPaid = r.emisPaidBeforeTracking() == null ? 0 : r.emisPaidBeforeTracking();
            recordPaidBeforeTracking(userId, loan, rows, alreadyPaid, today);
            loans.flush();
            audit.record(userId, AUDIT_ENTITY, loan.getId(), AuditAction.CREATED, AuditService.diff(Map.of(), loan.snapshot()));
            log.info("Loan created loanId={} type={} installments={}", loan.getId(), loan.getType(), rows.size());
            return loan.getId();
        });
        return get(userId, id);
    }

    @Transactional
    public LoanResponse update(UUID userId, UUID loanId, UpdateLoanRequest r) {
        Loan loan = lock(userId, loanId);
        Map<String, Object> before = loan.snapshot();
        if (r.name() != null) {
            loan.rename(r.name());
        }
        if (r.lender() != null) {
            loan.setLender(clean(r.lender()));
        }
        if (r.type() != null) {
            loan.setType(r.type());
        }
        if (r.notes() != null) {
            loan.setNotes(clean(r.notes()));
        }
        if (r.paymentAccountId() != null) {
            loan.setPaymentAccountId(sameCurrency(accounts.requireForTransaction(userId, r.paymentAccountId(), "paymentAccountId",
                    loan.getPaymentAccountId()), loan.getCurrency()).id());
        }
        if (r.interestCategoryId() != null) {
            loan.setInterestCategoryId(interestCategory(userId, r.interestCategoryId(), loan.getInterestCategoryId()));
        }
        boolean termsChanged = Stream.of(r.principal(), r.annualRate(), r.tenureMonths(), r.emiAmount(), r.firstEmiDate())
                .anyMatch(Objects::nonNull);
        if (termsChanged) {
            changeTerms(userId, loan, r);
        }
        loans.flush();
        Map<String, Object> changes = AuditService.diff(before, loan.snapshot());
        if (!changes.isEmpty()) {
            audit.record(userId, AUDIT_ENTITY, loanId, AuditAction.UPDATED, changes);
        }
        return get(userId, loanId);
    }

    /** Removes a loan entered by mistake; its disbursement entry is voided. Loans with payments are kept. */
    @Transactional
    public void delete(UUID userId, UUID loanId) {
        Loan loan = lock(userId, loanId);
        if (payments.hasActive(loanId)) {
            throw new ApiException(ErrorCode.LOAN_HAS_PAYMENTS, "This loan has payments. Void them first (latest first).");
        }
        transactions.voidLoanTransactions(userId, nonNull(loan.getDisbursementTransactionId()), "Loan deleted");
        loan.markDeleted(clock.instant());
        loans.flush();
        audit.record(userId, AUDIT_ENTITY, loanId, AuditAction.DELETED, null);
        log.info("Loan deleted loanId={}", loanId);
    }

    /** Pays the next unpaid installment from an account, split into principal and interest as scheduled. */
    @Transactional
    public LoanResponse payEmi(UUID userId, UUID loanId, EmiPaymentRequest r, String idempotencyKey) {
        Map<String, Object> keyed = new HashMap<>();
        keyed.put("loanId", loanId);
        keyed.put("payment", r);
        idempotency.execute(userId, idempotencyKey, "PAY_EMI", keyed, () -> {
            Loan loan = lock(userId, loanId);
            LocalDate today = preferences.today(userId);
            State s = state(loan, installments.findByLoanIdOrderByNumberAsc(loanId), activePayments(loanId), today);
            if (s.outstanding().signum() <= 0 || s.nextUnpaid() == null) {
                throw new ApiException(ErrorCode.LOAN_CLOSED, "This loan is fully repaid.");
            }
            LoanInstallment next = s.nextUnpaid();
            if (r.installmentNumber() != null && r.installmentNumber() != next.getNumber()) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, r.installmentNumber() < next.getNumber()
                        ? "Installment " + r.installmentNumber() + " is already paid."
                        : "Pay installment " + next.getNumber() + " first; EMIs are paid in order.");
            }
            LocalDate date = paymentDate(r.paymentDate(), loan, today);
            AccountRef account = sameCurrency(accounts.requireForTransaction(userId,
                    r.accountId() != null ? r.accountId() : loan.getPaymentAccountId(), "accountId", null), loan.getCurrency());
            String label = "EMI " + next.getNumber() + " · " + loan.getName();
            UUID principalTx = next.getPrincipalAmount().signum() > 0 ? transactions.recordLoanMovement(userId, new LoanMovement(
                    TransactionType.LOAN_PRINCIPAL, loanId, account.id(), loan.getCurrency(), next.getPrincipalAmount(), date,
                    null, label + " (principal)", loan.getLender())) : null;
            UUID interestTx = next.getInterestAmount().signum() > 0 ? transactions.recordLoanMovement(userId, new LoanMovement(
                    TransactionType.EXPENSE, loanId, account.id(), loan.getCurrency(), next.getInterestAmount(), date,
                    loan.getInterestCategoryId(), label + " (interest)", loan.getLender())) : null;
            LoanPayment payment = payments.saveAndFlush(new LoanPayment(userId, loanId, LoanPayment.Kind.EMI, next.getNumber(), date,
                    next.getPrincipalAmount(), next.getInterestAmount(), account.id(), principalTx, interestTx, clock.instant()));
            audit.record(userId, AUDIT_ENTITY, loanId, AuditAction.REPAID, Map.of("paymentId", payment.getId(),
                    "installment", next.getNumber(), "principal", plain(next.getPrincipalAmount()), "interest", plain(next.getInterestAmount())));
            log.info("EMI paid loanId={} installment={}", loanId, next.getNumber());
            return payment.getId();
        });
        return get(userId, loanId);
    }

    /** Extra principal. The EMI stays the same; unpaid installments are recalculated, so the loan ends sooner. */
    @Transactional
    public LoanResponse prepay(UUID userId, UUID loanId, PrepaymentRequest r, String idempotencyKey) {
        Map<String, Object> keyed = new HashMap<>();
        keyed.put("loanId", loanId);
        keyed.put("prepayment", r);
        idempotency.execute(userId, idempotencyKey, "PREPAY_LOAN", keyed, () -> {
            Loan loan = lock(userId, loanId);
            LocalDate today = preferences.today(userId);
            State s = state(loan, installments.findByLoanIdOrderByNumberAsc(loanId), activePayments(loanId), today);
            if (s.outstanding().signum() <= 0) {
                throw new ApiException(ErrorCode.LOAN_CLOSED, "This loan is fully repaid.");
            }
            MoneyRules.requireValidAmount("amount", r.amount(), loan.getCurrency());
            if (r.amount().compareTo(s.outstanding()) > 0) {
                throw new ApiException(ErrorCode.REPAYMENT_EXCEEDS_OUTSTANDING, "Prepayment is more than the outstanding principal "
                        + MoneyRules.scaled(s.outstanding(), loan.getCurrency()) + " " + loan.getCurrency());
            }
            LocalDate date = paymentDate(r.paymentDate(), loan, today);
            AccountRef account = sameCurrency(accounts.requireForTransaction(userId,
                    r.accountId() != null ? r.accountId() : loan.getPaymentAccountId(), "accountId", null), loan.getCurrency());
            UUID tx = transactions.recordLoanMovement(userId, new LoanMovement(TransactionType.LOAN_PRINCIPAL, loanId, account.id(),
                    loan.getCurrency(), r.amount(), date, null, "Prepayment · " + loan.getName(), loan.getLender()));
            LoanPayment payment = payments.saveAndFlush(new LoanPayment(userId, loanId, LoanPayment.Kind.PREPAYMENT, null, date,
                    r.amount(), BigDecimal.ZERO, account.id(), tx, null, clock.instant()));
            regenerate(loan, s.lastPaidNumber(), s.outstanding().subtract(r.amount()));
            audit.record(userId, AUDIT_ENTITY, loanId, AuditAction.REPAID,
                    Map.of("paymentId", payment.getId(), "prepayment", plain(r.amount())));
            log.info("Loan prepaid loanId={}", loanId);
            return payment.getId();
        });
        return get(userId, loanId);
    }

    /** Undoes the most recent payment (its ledger entries are voided). Repeating it is harmless. */
    @Transactional
    public LoanResponse voidPayment(UUID userId, UUID loanId, UUID paymentId) {
        Loan loan = lock(userId, loanId);
        List<LoanPayment> all = payments.findByLoanIdOrderByCreatedAtAsc(loanId);
        LoanPayment target = all.stream().filter(p -> p.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Loan payment"));
        if (target.isVoided()) {
            return get(userId, loanId);
        }
        LoanPayment latest = all.stream().filter(p -> !p.isVoided()).reduce((a, b) -> b).orElseThrow();
        if (!latest.getId().equals(paymentId)) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Only the most recent payment can be voided. Void the later ones first.");
        }
        transactions.voidLoanTransactions(userId, nonNull(target.getPrincipalTransactionId(), target.getInterestTransactionId()),
                "Loan payment voided");
        target.markVoided(clock.instant());
        payments.flush();
        if (target.getKind() == LoanPayment.Kind.PREPAYMENT) {
            State s = state(loan, installments.findByLoanIdOrderByNumberAsc(loanId), activePayments(loanId), preferences.today(userId));
            regenerate(loan, s.lastPaidNumber(), s.outstanding());
        }
        audit.record(userId, AUDIT_ENTITY, loanId, AuditAction.REPAYMENT_VOIDED, Map.of("paymentId", paymentId));
        log.info("Loan payment voided loanId={} paymentId={}", loanId, paymentId);
        return get(userId, loanId);
    }

    // ---- schedule state ----

    /** Everything derived from the stored schedule and active payments. */
    record State(BigDecimal outstanding, BigDecimal principalPaid, BigDecimal interestPaid, int lastPaidNumber,
                 LoanInstallment nextUnpaid, int missedCount, List<InstallmentResponse> installments, List<LoanPayment> payments,
                 BigDecimal remainingInterest) {
    }

    private State state(Loan loan, List<LoanInstallment> rows, List<LoanPayment> active, LocalDate today) {
        String c = loan.getCurrency();
        BigDecimal principalPaid = active.stream().map(LoanPayment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interestPaid = active.stream().map(LoanPayment::getInterestAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Integer, LoanPayment> paidEmis = active.stream().filter(p -> p.getKind() == LoanPayment.Kind.EMI)
                .collect(Collectors.toMap(LoanPayment::getInstallmentNumber, p -> p, (a, b) -> a));
        int lastPaid = paidEmis.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        LoanInstallment nextUnpaid = null;
        int missed = 0;
        boolean dueAssigned = false;
        BigDecimal remainingInterest = BigDecimal.ZERO;
        List<InstallmentResponse> out = new ArrayList<>();
        for (LoanInstallment row : rows) {
            LoanPayment paid = paidEmis.get(row.getNumber());
            InstallmentResponse.Status status;
            if (paid != null) {
                status = InstallmentResponse.Status.PAID;
            } else {
                if (nextUnpaid == null) {
                    nextUnpaid = row;
                }
                remainingInterest = remainingInterest.add(row.getInterestAmount());
                if (row.getDueDate().isBefore(today)) {
                    status = InstallmentResponse.Status.MISSED;
                    missed++;
                } else if (!dueAssigned) {
                    status = InstallmentResponse.Status.DUE;
                    dueAssigned = true;
                } else {
                    status = InstallmentResponse.Status.UPCOMING;
                }
            }
            out.add(new InstallmentResponse(row.getNumber(), row.getDueDate(), MoneyRules.scaled(row.getEmiAmount(), c),
                    MoneyRules.scaled(row.getPrincipalAmount(), c), MoneyRules.scaled(row.getInterestAmount(), c),
                    MoneyRules.scaled(row.getOpeningBalance(), c), MoneyRules.scaled(row.getClosingBalance(), c), status,
                    paid == null ? null : paid.getPaymentDate(), paid == null ? null : paid.getId()));
        }
        BigDecimal outstanding = loan.getPrincipal().subtract(principalPaid).max(BigDecimal.ZERO);
        return new State(outstanding, principalPaid, interestPaid, lastPaid, nextUnpaid, missed, out, active, remainingInterest);
    }

    private Map<UUID, State> states(List<Loan> list, LocalDate today) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = list.stream().map(Loan::getId).collect(Collectors.toSet());
        Map<UUID, List<LoanInstallment>> rows = installments.findByLoanIdInOrderByNumberAsc(ids).stream()
                .collect(Collectors.groupingBy(LoanInstallment::getLoanId));
        Map<UUID, List<LoanPayment>> paid = payments.findActiveByLoanIds(ids).stream()
                .sorted(Comparator.comparing(LoanPayment::getCreatedAt))
                .collect(Collectors.groupingBy(LoanPayment::getLoanId));
        Map<UUID, State> result = new HashMap<>();
        for (Loan loan : list) {
            result.put(loan.getId(), state(loan, rows.getOrDefault(loan.getId(), List.of()),
                    paid.getOrDefault(loan.getId(), List.of()), today));
        }
        return result;
    }

    /** Replaces every unpaid installment after {@code afterNumber} with a fresh schedule from {@code opening}. */
    private void regenerate(Loan loan, int afterNumber, BigDecimal opening) {
        installments.deleteAfter(loan.getId(), afterNumber);
        if (opening.signum() > 0) {
            int scale = MoneyRules.fractionDigits(loan.getCurrency());
            List<Amortization.Row> rows = Amortization.schedule(opening, loan.getAnnualRate(), loan.getEmiAmount(),
                    loan.getFirstEmiDate(), afterNumber + 1, null, scale);
            installments.saveAll(rows.stream().map(row -> new LoanInstallment(loan.getId(), row)).toList());
        }
        installments.flush();
    }

    private void changeTerms(UUID userId, Loan loan, UpdateLoanRequest r) {
        if (payments.hasActive(loan.getId())) {
            throw new ApiException(ErrorCode.LOAN_HAS_PAYMENTS,
                    "Loan terms can't change once payments are recorded. Prepay or void payments instead.");
        }
        BigDecimal principal = r.principal() != null ? r.principal() : loan.getPrincipal();
        BigDecimal rate = r.annualRate() != null ? r.annualRate() : loan.getAnnualRate();
        int tenure = r.tenureMonths() != null ? r.tenureMonths() : loan.getTenureMonths();
        LocalDate firstEmi = r.firstEmiDate() != null ? r.firstEmiDate() : loan.getFirstEmiDate();
        String currency = loan.getCurrency();
        MoneyRules.requireValidAmount("principal", principal, currency);
        requireRateScale(rate);
        if (firstEmi.isBefore(loan.getStartDate())) {
            throw new FieldValidationException("firstEmiDate", "can't be before the disbursement date");
        }
        int scale = MoneyRules.fractionDigits(currency);
        boolean recompute = r.emiAmount() == null && (r.principal() != null || r.annualRate() != null || r.tenureMonths() != null);
        BigDecimal emi = r.emiAmount() != null ? validEmi(r.emiAmount(), currency)
                : recompute ? Amortization.emi(principal, rate, tenure, scale) : loan.getEmiAmount();
        List<Amortization.Row> rows = Amortization.schedule(principal, rate, emi, firstEmi, 1, tenure, scale);
        boolean principalChanged = principal.compareTo(loan.getPrincipal()) != 0;
        loan.setTerms(principal, rate, tenure, emi, loan.getStartDate(), firstEmi);
        installments.deleteAfter(loan.getId(), 0);
        installments.saveAll(rows.stream().map(row -> new LoanInstallment(loan.getId(), row)).toList());
        if (principalChanged && loan.getDisbursementTransactionId() != null) {
            transactions.voidLoanTransactions(userId, nonNull(loan.getDisbursementTransactionId()), "Loan amount changed");
            UUID tx = transactions.recordLoanMovement(userId, new LoanMovement(TransactionType.LOAN_DISBURSEMENT, loan.getId(),
                    loan.getDisbursementAccountId(), currency, principal, loan.getStartDate(), null,
                    "Loan disbursed · " + loan.getName(), loan.getLender()));
            loan.setDisbursement(loan.getDisbursementAccountId(), tx);
        }
    }

    private void recordPaidBeforeTracking(UUID userId, Loan loan, List<Amortization.Row> rows, int count, LocalDate today) {
        if (count == 0) {
            return;
        }
        if (count > rows.size()) {
            throw new FieldValidationException("emisPaidBeforeTracking", "is more than the " + rows.size() + " installments in the schedule");
        }
        if (rows.get(count - 1).dueDate().isAfter(today)) {
            throw new FieldValidationException("emisPaidBeforeTracking", "can only include installments already due");
        }
        List<LoanPayment> history = new ArrayList<>();
        for (Amortization.Row row : rows.subList(0, count)) {
            history.add(new LoanPayment(userId, loan.getId(), LoanPayment.Kind.EMI, row.number(), row.dueDate(),
                    row.principal(), row.interest(), null, null, null, clock.instant()));
        }
        payments.saveAll(history);
    }

    // ---- responses ----

    private List<LoanResponse> toResponses(UUID userId, List<Loan> list, boolean withPayments) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = preferences.today(userId);
        Map<UUID, State> states = states(list, today);
        Set<UUID> accountIds = new HashSet<>();
        list.forEach(l -> Stream.of(l.getDisbursementAccountId(), l.getPaymentAccountId()).filter(Objects::nonNull).forEach(accountIds::add));
        Map<UUID, List<LoanPayment>> allPayments = new HashMap<>();
        if (withPayments) {
            for (Loan l : list) {
                List<LoanPayment> ps = payments.findByLoanIdOrderByCreatedAtAsc(l.getId());
                allPayments.put(l.getId(), ps);
                ps.stream().map(LoanPayment::getAccountId).filter(Objects::nonNull).forEach(accountIds::add);
            }
        }
        Map<UUID, AccountRef> accountRefs = accounts.refs(userId, accountIds);
        Map<UUID, CategoryRef> categoryRefs = categories.refs(userId, list.stream().map(Loan::getInterestCategoryId).collect(Collectors.toSet()));

        return list.stream().map(l -> {
            State s = states.get(l.getId());
            String c = l.getCurrency();
            boolean closed = s.outstanding().signum() == 0;
            InstallmentResponse next = s.nextUnpaid() == null ? null : s.installments().stream()
                    .filter(i -> i.number() == s.nextUnpaid().getNumber()).findFirst().orElse(null);
            LocalDate end = s.installments().isEmpty() ? null : s.installments().getLast().dueDate();
            if (closed) {
                end = s.payments().stream().map(LoanPayment::getPaymentDate).max(Comparator.naturalOrder()).orElse(end);
            }
            CategoryRef category = categoryRefs.get(l.getInterestCategoryId());
            List<LoanPaymentResponse> history = withPayments ? allPayments.get(l.getId()).stream()
                    .map(p -> toResponse(p, c, accountRefs)).toList() : null;
            int paidCount = (int) s.installments().stream().filter(i -> i.status() == InstallmentResponse.Status.PAID).count();
            return new LoanResponse(l.getId(), l.getName(), l.getLender(), l.getType(), c,
                    MoneyRules.scaled(l.getPrincipal(), c), l.getAnnualRate().stripTrailingZeros(), l.getTenureMonths(),
                    MoneyRules.scaled(l.getEmiAmount(), c), l.getStartDate(), l.getFirstEmiDate(),
                    ref(accountRefs, l.getDisbursementAccountId()), ref(accountRefs, l.getPaymentAccountId()),
                    category == null ? null : new LoanResponse.Ref(category.id(), category.name()),
                    closed ? LoanResponse.Status.CLOSED : LoanResponse.Status.ACTIVE,
                    MoneyRules.scaled(s.outstanding(), c), MoneyRules.scaled(s.principalPaid(), c), MoneyRules.scaled(s.interestPaid(), c),
                    paidCount, s.installments().size() - paidCount, s.missedCount(), next,
                    MoneyRules.scaled(s.interestPaid().add(s.remainingInterest()), c), end, l.getNotes(), history,
                    l.getCreatedAt(), l.getUpdatedAt());
        }).toList();
    }

    private static LoanPaymentResponse toResponse(LoanPayment p, String c, Map<UUID, AccountRef> accountRefs) {
        return new LoanPaymentResponse(p.getId(), p.getKind(), p.getInstallmentNumber(), p.getPaymentDate(),
                MoneyRules.scaled(p.getPrincipalAmount(), c), MoneyRules.scaled(p.getInterestAmount(), c),
                MoneyRules.scaled(p.getPrincipalAmount().add(p.getInterestAmount()), c), ref(accountRefs, p.getAccountId()),
                p.getAccountId() != null, p.isVoided(), p.getVoidedAt(), p.getCreatedAt());
    }

    private static LoanResponse.Ref ref(Map<UUID, AccountRef> refs, UUID id) {
        AccountRef a = id == null ? null : refs.get(id);
        return a == null ? null : new LoanResponse.Ref(a.id(), a.name());
    }

    private static List<LoanSummaryResponse.UpcomingEmi> upcoming(List<Loan> list, Map<UUID, State> states, LocalDate today, int days) {
        LocalDate horizon = today.plusDays(days);
        List<LoanSummaryResponse.UpcomingEmi> out = new ArrayList<>();
        for (Loan l : list) {
            for (InstallmentResponse i : states.get(l.getId()).installments()) {
                if (i.status() == InstallmentResponse.Status.MISSED
                        || (i.status() != InstallmentResponse.Status.PAID && !i.dueDate().isAfter(horizon))) {
                    out.add(new LoanSummaryResponse.UpcomingEmi(l.getId(), l.getName(), l.getLender(), l.getCurrency(), i.number(),
                            i.dueDate(), i.emiAmount(), i.principalAmount(), i.interestAmount(), i.status()));
                }
            }
        }
        out.sort(Comparator.comparing(LoanSummaryResponse.UpcomingEmi::dueDate));
        return out;
    }

    // ---- helpers ----

    private Loan load(UUID userId, UUID loanId) {
        return loans.findActive(loanId, userId).orElseThrow(() -> new ResourceNotFoundException("Loan"));
    }

    private Loan lock(UUID userId, UUID loanId) {
        return loans.findForUpdate(loanId, userId).orElseThrow(() -> new ResourceNotFoundException("Loan"));
    }

    private List<LoanPayment> activePayments(UUID loanId) {
        return payments.findByLoanIdOrderByCreatedAtAsc(loanId).stream().filter(p -> !p.isVoided()).toList();
    }

    private UUID interestCategory(UUID userId, UUID requested, UUID current) {
        if (requested != null) {
            return categories.requireForTransaction(userId, requested, CategoryType.EXPENSE, current).id();
        }
        return categories.findActiveBySystemCode(userId, INTEREST_CATEGORY_CODE).map(CategoryRef::id)
                .orElseThrow(() -> new FieldValidationException("interestCategoryId",
                        "is required (the default Interest category is missing or deactivated)"));
    }

    private static AccountRef sameCurrency(AccountRef account, String currency) {
        if (!account.currency().equals(currency)) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH,
                    "Account '" + account.name() + "' uses " + account.currency() + ", the loan uses " + currency);
        }
        return account;
    }

    private static BigDecimal validEmi(BigDecimal emi, String currency) {
        MoneyRules.requireValidAmount("emiAmount", emi, currency);
        return emi;
    }

    private static void requireRateScale(BigDecimal rate) {
        if (rate.stripTrailingZeros().scale() > 4) {
            throw new FieldValidationException("annualRate", "can have at most 4 decimals");
        }
    }

    private LocalDate paymentDate(LocalDate requested, Loan loan, LocalDate today) {
        LocalDate date = requested != null ? requested : today;
        requireNotFuture("paymentDate", date, today);
        if (date.isBefore(loan.getStartDate())) {
            throw new FieldValidationException("paymentDate", "can't be before the loan started (" + loan.getStartDate() + ")");
        }
        return date;
    }

    private static void requireNotFuture(String field, LocalDate date, LocalDate today) {
        if (date.isAfter(today.plusDays(1))) {
            throw new FieldValidationException(field, "can't be in the future");
        }
    }

    private static List<UUID> nonNull(UUID... ids) {
        return Stream.of(ids).filter(Objects::nonNull).toList();
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
