package com.paisatrack.modules.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.paisatrack.modules.budget.dto.BudgetResponse;
import com.paisatrack.modules.report.ReportService.Window;
import com.paisatrack.modules.report.dto.ReportResponse;
import com.paisatrack.modules.report.dto.ReportResponse.Insight;
import com.paisatrack.modules.report.dto.ReportResponse.Insight.Tone;
import com.paisatrack.modules.report.dto.ReportResponse.Kind;

/**
 * Plain-language observations drawn only from the report's own numbers. Each rule fires only when
 * its data exists and the difference is meaningful; nothing is estimated or predicted.
 */
final class Insights {

    /** Changes smaller than this (in %) aren't worth mentioning. */
    private static final BigDecimal NOTABLE_CHANGE = BigDecimal.valueOf(10);
    /** A category rise is mentioned when it is at least this big (in %)... */
    private static final BigDecimal NOTABLE_CATEGORY_RISE = BigDecimal.valueOf(25);
    /** ...and at least this share (in %) of the period's spending. */
    private static final BigDecimal NOTABLE_CATEGORY_SHARE = BigDecimal.valueOf(10);
    private static final BigDecimal NOTABLE_RATE_SHIFT = BigDecimal.valueOf(5);

    private Insights() {
    }

    static List<Insight> of(Kind kind, Window w, ReportResponse.Summary s, ReportResponse.Comparison cmp,
                            List<ReportResponse.CategoryLine> categories, List<ReportResponse.BudgetLine> budgets,
                            ReportResponse.Loans loans, String c) {
        List<Insight> out = new ArrayList<>();
        if (s.incomeCount() == 0 && s.expenseCount() == 0) {
            out.add(new Insight("NO_ACTIVITY", Tone.NEUTRAL, "No income or spending recorded in this period yet."));
        }
        String than = than(kind, w);

        // Spending vs the comparison period.
        if (cmp.expenseChange() != null && s.expense().signum() > 0 && cmp.expenseChange().abs().compareTo(NOTABLE_CHANGE) >= 0) {
            boolean up = cmp.expenseChange().signum() > 0;
            out.add(new Insight("SPENDING_CHANGE", up ? Tone.WARNING : Tone.POSITIVE,
                    "Spending is " + (up ? "up " : "down ") + whole(cmp.expenseChange().abs()) + "% on " + than + " ("
                            + money(s.expense(), c) + " vs " + money(cmp.expense(), c) + ")."));
        }

        // Biggest category, and the biggest notable rise.
        if (!categories.isEmpty() && s.expense().signum() > 0) {
            ReportResponse.CategoryLine top = categories.getFirst();
            out.add(new Insight("TOP_CATEGORY", Tone.NEUTRAL, top.name() + " was your biggest spending category: "
                    + money(top.total(), c) + " (" + whole(top.share()) + "% of spending)."));
            Optional<ReportResponse.CategoryLine> rise = categories.stream()
                    .filter(x -> x.change() != null && x.change().compareTo(NOTABLE_CATEGORY_RISE) >= 0)
                    .filter(x -> x.share() != null && x.share().compareTo(NOTABLE_CATEGORY_SHARE) >= 0)
                    .max(Comparator.comparing(x -> x.total().subtract(x.previousTotal())));
            rise.ifPresent(x -> out.add(new Insight("CATEGORY_RISE", Tone.WARNING, x.name() + " spending rose " + whole(x.change())
                    + "% on " + than + " (" + money(x.total(), c) + " vs " + money(x.previousTotal(), c) + ").")));
        }

        // Earning vs spending, and the savings rate.
        if (s.income().signum() > 0 && s.expense().compareTo(s.income()) > 0) {
            out.add(new Insight("OVERSPENT_INCOME", Tone.WARNING, "You spent " + money(s.expense().subtract(s.income()), c)
                    + " more than you earned in this period."));
        } else if (s.savingsRate() != null && cmp.savingsRate() != null
                && s.savingsRate().subtract(cmp.savingsRate()).abs().compareTo(NOTABLE_RATE_SHIFT) >= 0) {
            boolean up = s.savingsRate().compareTo(cmp.savingsRate()) > 0;
            out.add(new Insight("SAVINGS_RATE", up ? Tone.POSITIVE : Tone.WARNING, "Savings rate " + (up ? "rose" : "fell")
                    + " from " + whole(cmp.savingsRate()) + "% to " + whole(s.savingsRate()) + "% compared with " + than + "."));
        }

        // Budgets over or near their limit.
        if (budgets != null) {
            budgets.stream().filter(b -> b.status() == BudgetResponse.Status.EXCEEDED).limit(2).forEach(b -> out.add(new Insight(
                    "BUDGET_EXCEEDED", Tone.WARNING, label(b.name()) + " is over by " + money(b.remaining().negate(), c) + ".")));
            budgets.stream().filter(b -> b.status() == BudgetResponse.Status.ALERT).limit(1).forEach(b -> out.add(new Insight(
                    "BUDGET_ALERT", Tone.WARNING, label(b.name()) + " has used " + whole(b.percentUsed()) + "% with "
                            + money(b.remaining(), c) + " left.")));
        }

        // Where loan money went.
        if (s.interest().signum() > 0) {
            out.add(new Insight("INTEREST_PAID", Tone.NEUTRAL, money(s.interest(), c) + " of spending was loan interest ("
                    + whole(ReportService.percent(s.interest(), s.expense())) + "%)."));
        }
        if (loans != null && loans.emisDueSoon().signum() > 0) {
            out.add(new Insight("EMIS_DUE", Tone.NEUTRAL, "EMIs due in the next 30 days total " + money(loans.emisDueSoon(), c) + "."));
        }
        if (s.netInvested().signum() > 0) {
            out.add(new Insight("INVESTED", Tone.POSITIVE, "You moved " + money(s.netInvested(), c) + " into investments."));
        }
        return out;
    }

    private static String than(Kind kind, Window w) {
        String unit = switch (kind) {
            case DAILY -> "yesterday";
            case WEEKLY -> "last week";
            case MONTHLY -> "last month";
            case YEARLY -> "last year";
            case CUSTOM -> "the previous " + w.days() + " days";
        };
        return w.samePoint() ? "the same point " + unit : unit;
    }

    private static String label(String budgetName) {
        return budgetName.toLowerCase().endsWith("budget") ? budgetName : budgetName + " budget";
    }

    private static String whole(BigDecimal pct) {
        return pct.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String money(BigDecimal amount, String c) {
        return ReportService.money(amount, c);
    }
}
