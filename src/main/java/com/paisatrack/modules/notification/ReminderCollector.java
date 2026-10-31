package com.paisatrack.modules.notification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paisatrack.modules.budget.BudgetService;
import com.paisatrack.modules.budget.dto.BudgetResponse;
import com.paisatrack.modules.debt.DebtDirection;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtResponse;
import com.paisatrack.modules.income.IncomeSourceService;
import com.paisatrack.modules.income.dto.ExpectedIncomeResponse;
import com.paisatrack.modules.loan.LoanService;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.recurring.RecurringService;
import com.paisatrack.modules.recurring.RecurringType;
import com.paisatrack.modules.recurring.dto.OccurrenceResponse;
import com.paisatrack.platform.money.MoneyFormat;

/**
 * Builds the reminders a user should have today from the modules that own the facts: EMIs,
 * recurring occurrences, lending/borrowing due dates, budget thresholds and expected salary.
 * Each reminder carries a dedupe key, so it is stored at most once per item and stage.
 */
@Component
class ReminderCollector {

    record Reminder(NotificationType type, NotificationStage stage, String title, String message, UUID subjectId,
                    LocalDate dueDate, String dedupeKey) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final LoanService loans;
    private final RecurringService recurring;
    private final DebtService debts;
    private final BudgetService budgets;
    private final IncomeSourceService incomeSources;

    ReminderCollector(LoanService loans, RecurringService recurring, DebtService debts, BudgetService budgets,
                      IncomeSourceService incomeSources) {
        this.loans = loans;
        this.recurring = recurring;
        this.debts = debts;
        this.budgets = budgets;
        this.incomeSources = incomeSources;
    }

    /** {@code daysBefore} holds the window of each enabled type; disabled types are absent. */
    List<Reminder> collect(UUID userId, LocalDate today, Map<NotificationType, Integer> daysBefore) {
        List<Reminder> out = new ArrayList<>();
        if (daysBefore.containsKey(NotificationType.EMI)) {
            emis(userId, today, daysBefore.get(NotificationType.EMI), out);
        }
        int recurringWindow = daysBefore.entrySet().stream()
                .filter(e -> e.getKey() == NotificationType.BILL || e.getKey() == NotificationType.SUBSCRIPTION
                        || e.getKey() == NotificationType.RECURRING)
                .mapToInt(Map.Entry::getValue).max().orElse(-1);
        if (recurringWindow >= 0) {
            recurring(userId, today, recurringWindow, daysBefore, out);
        }
        Integer lending = daysBefore.get(NotificationType.LENDING);
        Integer borrowing = daysBefore.get(NotificationType.BORROWING);
        if (lending != null || borrowing != null) {
            debts(userId, today, lending, borrowing, out);
        }
        if (daysBefore.containsKey(NotificationType.BUDGET)) {
            budgets(userId, today, out);
        }
        if (daysBefore.containsKey(NotificationType.SALARY)) {
            salary(userId, today, daysBefore.get(NotificationType.SALARY), out);
        }
        out.sort(Comparator.comparing(Reminder::dueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private void emis(UUID userId, LocalDate today, int days, List<Reminder> out) {
        for (LoanSummaryResponse.UpcomingEmi e : loans.upcoming(userId, days)) {
            NotificationStage stage = NotificationStage.of(e.dueDate(), today, days);
            if (stage == null) {
                continue;
            }
            String amount = money(e.emiAmount(), e.currency());
            String title = switch (stage) {
                case OVERDUE -> e.loanName() + " EMI overdue";
                case DUE -> e.loanName() + " EMI due today";
                default -> e.loanName() + " EMI due " + DAY.format(e.dueDate());
            };
            String message = stage == NotificationStage.OVERDUE
                    ? "EMI " + e.number() + " of " + amount + " was due on " + DAY.format(e.dueDate()) + "."
                    : "EMI " + e.number() + " of " + amount + " is due" + (stage == NotificationStage.DUE ? " today." : " on " + DAY.format(e.dueDate()) + ".");
            out.add(new Reminder(NotificationType.EMI, stage, title, message, e.loanId(), e.dueDate(),
                    "EMI:" + e.loanId() + ":" + e.number() + ":" + stage));
        }
    }

    private void recurring(UUID userId, LocalDate today, int window, Map<NotificationType, Integer> daysBefore, List<Reminder> out) {
        for (OccurrenceResponse o : recurring.summary(userId, window).expected()) {
            NotificationType type = typeOf(o);
            Integer days = daysBefore.get(type);
            if (days == null) {
                continue;
            }
            NotificationStage stage = NotificationStage.of(o.dueDate(), today, days);
            if (stage == null) {
                continue;
            }
            boolean income = o.type() == RecurringType.INCOME;
            String title = switch (stage) {
                case OVERDUE -> o.ruleName() + (income ? " not received yet" : " overdue");
                case DUE -> o.ruleName() + " due today";
                default -> o.ruleName() + " due " + DAY.format(o.dueDate());
            };
            String message = "Expected " + money(o.expectedAmount(), o.currency())
                    + (stage == NotificationStage.OVERDUE ? " on " + DAY.format(o.dueDate()) : "")
                    + (income ? ". Confirm it once received." : ". Confirm it once paid.");
            out.add(new Reminder(type, stage, title, message, o.id(), o.dueDate(), "OCCURRENCE:" + o.id() + ":" + stage));
        }
    }

    private static NotificationType typeOf(OccurrenceResponse o) {
        if (o.type() == RecurringType.INCOME) {
            return NotificationType.RECURRING;
        }
        return switch (o.kind()) {
            case SUBSCRIPTION -> NotificationType.SUBSCRIPTION;
            case RENT, BILL, INSURANCE -> NotificationType.BILL;
            case INVESTMENT, OTHER -> NotificationType.RECURRING;
        };
    }

    /** Lending and borrowing: by due date, or by the reminder date when there is no due date. */
    private void debts(UUID userId, LocalDate today, Integer lendingDays, Integer borrowingDays, List<Reminder> out) {
        int window = Math.max(lendingDays == null ? 0 : lendingDays, borrowingDays == null ? 0 : borrowingDays);
        for (DebtResponse d : debts.reminders(userId, window)) {
            boolean lent = d.direction() == DebtDirection.LENT;
            Integer days = lent ? lendingDays : borrowingDays;
            LocalDate date = d.dueDate() != null ? d.dueDate() : d.remindOn();
            if (days == null || date == null) {
                continue;
            }
            NotificationStage stage = NotificationStage.of(date, today, days);
            if (stage == null) {
                continue;
            }
            String person = d.person().name();
            String amount = money(d.outstanding(), d.currency());
            String title = lent ? person + " owes you " + amount : "Repay " + person + " " + amount;
            String when = d.dueDate() == null ? "Reminder for " + DAY.format(date)
                    : switch (stage) {
                        case OVERDUE -> "Was due on " + DAY.format(date);
                        case DUE -> "Due today";
                        default -> "Due on " + DAY.format(date);
                    };
            String message = when + (d.description() != null ? " · " + d.description() : "") + ".";
            out.add(new Reminder(lent ? NotificationType.LENDING : NotificationType.BORROWING, stage, title, message, d.id(), date,
                    "DEBT:" + d.id() + ":" + date + ":" + stage));
        }
    }

    /** One alert per budget period for the highest threshold reached so far. */
    private void budgets(UUID userId, LocalDate today, List<Reminder> out) {
        for (BudgetResponse b : budgets.list(userId, today, false)) {
            BudgetResponse.Utilization u = b.utilization();
            if (u == null || u.periodStart() == null || u.crossedThresholds() == null || u.crossedThresholds().isEmpty()) {
                continue;
            }
            int threshold = u.crossedThresholds().stream().mapToInt(Integer::intValue).max().orElseThrow();
            boolean exceeded = u.status() == BudgetResponse.Status.EXCEEDED;
            String label = b.name().toLowerCase(Locale.ROOT).endsWith("budget") ? b.name() : b.name() + " budget";
            String title = exceeded ? label + " exceeded" : label + " " + threshold + "% used";
            String message = "Spent " + money(u.spent(), b.currency()) + " of " + money(b.amount(), b.currency())
                    + " (" + DAY.format(u.periodStart()) + " – " + DAY.format(u.periodEnd()) + ").";
            String key = "BUDGET:" + b.id() + ":" + u.periodStart() + ":" + (exceeded ? "EXCEEDED" : threshold);
            out.add(new Reminder(NotificationType.BUDGET, NotificationStage.ALERT, title, message, b.id(), null, key));
        }
    }

    /** Expected income not yet received: last month (if overdue), this month and next (salary credited on the 1st). */
    private void salary(UUID userId, LocalDate today, int days, List<Reminder> out) {
        YearMonth month = YearMonth.from(today);
        for (YearMonth m : List.of(month.minusMonths(1), month, month.plusMonths(1))) {
            for (ExpectedIncomeResponse e : incomeSources.monthOverview(userId, m)) {
                if (e.expectedDate() == null || (e.status() != ExpectedIncomeResponse.Status.PENDING
                        && e.status() != ExpectedIncomeResponse.Status.OVERDUE)) {
                    continue;
                }
                NotificationStage stage = NotificationStage.of(e.expectedDate(), today, days);
                if (stage == null) {
                    continue;
                }
                String title = switch (stage) {
                    case OVERDUE -> e.sourceName() + " not received yet";
                    case DUE -> e.sourceName() + " expected today";
                    default -> e.sourceName() + " expected " + DAY.format(e.expectedDate());
                };
                String message = "Expected " + money(e.expectedAmount(), e.currency()) + " for " + m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ". Record it when it arrives.";
                out.add(new Reminder(NotificationType.SALARY, stage, title, message, e.sourceId(), e.expectedDate(),
                        "SALARY:" + e.sourceId() + ":" + m + ":" + stage));
            }
        }
    }

    private static String money(BigDecimal amount, String currency) {
        return MoneyFormat.format(amount, currency);
    }
}
