package com.paisatrack.modules.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.report.ReportService;
import com.paisatrack.modules.report.dto.ReportResponse;
import com.paisatrack.modules.transaction.TransactionQueryService;
import com.paisatrack.modules.transaction.TransactionType;
import com.paisatrack.modules.transaction.dto.TransactionFilter;
import com.paisatrack.modules.transaction.dto.TransactionResponse;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;

/**
 * Downloads: transactions (the same filters as the list) and reports (the same figures as the
 * report API), as CSV, Excel or PDF. Nothing is recalculated here, so a file always matches what the
 * app shows.
 */
@Service
public class ExportService {

    /** A file to send: name, type and content. */
    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    static final int MAX_ROWS = 20_000;
    private static final int PAGE = 100;
    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final TransactionQueryService transactions;
    private final ReportService reports;
    private final UserPreferencesService preferences;

    ExportService(TransactionQueryService transactions, ReportService reports, UserPreferencesService preferences) {
        this.transactions = transactions;
        this.reports = reports;
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public ExportFile transactions(UUID userId, TransactionFilter filter, ExportFormat format) {
        if (filter.from() != null && filter.to() != null && ChronoUnit.DAYS.between(filter.from(), filter.to()) > 3 * 366) {
            throw new FieldValidationException("to", "range can be at most three years");
        }
        List<TransactionResponse> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            var result = transactions.list(userId, new TransactionFilter(filter.type(), filter.from(), filter.to(), filter.accountId(),
                    filter.categoryId(), filter.paymentMethod(), filter.incomeSourceId(), filter.q(), filter.minAmount(), filter.maxAmount(),
                    filter.includeVoided(), page, PAGE, TransactionFilter.SortField.DATE, TransactionFilter.SortDirection.ASC));
            all.addAll(result.items());
            if (all.size() > MAX_ROWS) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "More than " + MAX_ROWS + " transactions match. Export a shorter date range.");
            }
            if (page + 1 >= result.totalPages()) {
                break;
            }
        }

        Table list = Table.of("Transactions", "Date", "Type", "Status", "Amount", "Currency", "Account", "To account", "Amount received",
                "Received currency", "Category", "Description", "Merchant / payer", "Payment method", "Notes", "Id");
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        for (TransactionResponse t : all) {
            list.add(t.transactionDate(), label(t.type()), t.status().name(), t.amount(), t.currency(), name(t.account()),
                    name(t.toAccount()), t.toAmount(), t.toCurrency(), category(t), t.description(), t.counterparty(),
                    t.paymentMethod() == null ? null : t.paymentMethod().name(), t.notes(), t.id().toString());
            if (t.status().name().equals("ACTIVE")) {
                BigDecimal[] sum = totals.computeIfAbsent(t.currency(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                if (t.type() == TransactionType.INCOME) {
                    sum[0] = sum[0].add(t.amount());
                } else if (t.type() == TransactionType.EXPENSE) {
                    sum[1] = sum[1].add(t.amount());
                }
            }
        }
        Table summary = Table.of("Totals", "Currency", "Income", "Expenses", "Net");
        totals.forEach((c, s) -> summary.add(c, s[0], s[1], s[0].subtract(s[1])));

        String range = rangeText(filter.from(), filter.to());
        String name = "paisatrack-transactions" + (filter.from() != null ? "-" + filter.from() : "") + (filter.to() != null ? "_" + filter.to() : "");
        log.info("Export transactions format={} rows={}", format, all.size());
        return file(name, format, "Transactions" + (range.isEmpty() ? "" : " · " + range),
                List.of(all.size() + " transactions" + (Boolean.TRUE.equals(filter.includeVoided()) ? ", deleted ones included" : "")
                        + ". Totals count recorded income and expenses only; transfers and loan movements are listed, not totalled.",
                        "Generated " + preferences.today(userId) + " by PaisaTrack."),
                List.of(list, summary), List.of(list, summary));
    }

    /** One report in a file: summary, comparison, categories, money movements, accounts, insights. */
    @Transactional(readOnly = true)
    public ExportFile report(UUID userId, ReportResponse.Kind kind, LocalDate date, YearMonth month, Integer year, LocalDate from, LocalDate to,
                             UUID accountId, UUID categoryId, TransactionType type, String currency, ExportFormat format) {
        ReportResponse r = switch (kind) {
            case DAILY -> reports.daily(userId, date, currency);
            case WEEKLY -> reports.weekly(userId, date, currency);
            case MONTHLY -> reports.monthly(userId, month, currency);
            case YEARLY -> reports.yearly(userId, year, currency);
            case CUSTOM -> reports.custom(userId, from, to, accountId, categoryId, type, currency);
        };
        ReportResponse.Summary s = r.summary();
        Table summary = Table.of("Summary", "Item", "Amount (" + r.currency() + ")", "Previous period", "Change %")
                .add("Income", s.income(), r.comparison().income(), r.comparison().incomeChange())
                .add("Expenses", s.expense(), r.comparison().expense(), r.comparison().expenseChange())
                .add("of which loan interest", s.interest(), null, null)
                .add("Consumption (expenses without interest)", s.consumption(), r.comparison().consumption(), null)
                .add("Net cash flow", s.netCashFlow(), r.comparison().netCashFlow(), null)
                .add("Savings rate %", s.savingsRate(), r.comparison().savingsRate(), null)
                .add("Average per day", s.averageDailyExpense(), null, null)
                .add("Transfers (not income or spending)", s.transfers(), null, null)
                .add("Card bills paid", s.cardBillPayments(), null, null)
                .add("Invested", s.invested(), null, null)
                .add("Withdrawn from investments", s.withdrawn(), null, null)
                .add("Loan principal repaid", s.loanPrincipalRepaid(), null, null)
                .add("Loan money received (not income)", s.loanDisbursed(), null, null);
        if (r.lending() != null) {
            summary.add("Lent", r.lending().lent(), null, null)
                    .add("Repaid to you", r.lending().repaidToYou(), null, null)
                    .add("Borrowed", r.lending().borrowed(), null, null)
                    .add("You repaid", r.lending().youRepaid(), null, null);
        }
        if (r.salary() != null && r.salary().utilization() != null) {
            summary.add("Salary used %", r.salary().utilization(), null, null);
        }
        Table categories = Table.of("Spending by category", "Category", "Amount", "Share %", "Previous", "Change %", "Entries");
        r.expenseByCategory().forEach(c -> categories.add(c.name(), c.total(), c.share(), c.previousTotal(), c.change(), c.count()));
        Table series = Table.of(r.granularity() == ReportResponse.Granularity.MONTH ? "By month" : "By day", "Date", "Income", "Expenses", "Net");
        r.series().forEach(p -> series.add(p.date(), p.income(), p.expense(), p.net()));
        Table accounts = Table.of("Accounts", "Account", "Currency", "Money in", "Money out", "Net", "Balance now");
        r.accounts().forEach(a -> accounts.add(a.name(), a.currency(), a.inflow(), a.outflow(), a.net(), a.currentBalance()));
        Table insights = Table.of("Insights", "Insight");
        r.insights().forEach(i -> insights.add(i.message()));
        Table conversions = Table.of("Currency conversions", "Currency", "Converted entries", "Without a rate", "Rates from", "Rates to");
        r.conversions().forEach(c -> conversions.add(c.currency(), c.convertedEntries(), c.missingEntries(), c.firstRateDate(), c.lastRateDate()));

        List<Table> tables = new ArrayList<>(List.of(summary, categories, series, accounts, insights));
        if (!r.conversions().isEmpty()) {
            tables.add(conversions);
        }
        String period = rangeText(r.from(), r.to());
        log.info("Export report kind={} format={}", kind, format);
        return file("paisatrack-" + kind.name().toLowerCase() + "-report-" + r.from(), format,
                titleCase(kind.name()) + " report · " + period,
                List.of((r.complete() ? "Complete period. " : "So far, up to " + r.asOf() + ". ") + "Figures in " + r.currency() + ".", r.basis()),
                tables, List.of(summary, categories, accounts, insights));
    }

    private static ExportFile file(String name, ExportFormat format, String heading, List<String> lines, List<Table> tables, List<Table> pdfTables) {
        byte[] content = switch (format) {
            case CSV -> Table.csv(tables);
            case XLSX -> Table.xlsx(tables);
            case PDF -> Table.pdf(heading, lines, pdfTables);
        };
        return new ExportFile(name + "." + format.extension(), format.contentType(), content);
    }

    private static String rangeText(LocalDate from, LocalDate to) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
        if (from == null && to == null) {
            return "";
        }
        return (from == null ? "start" : f.format(from)) + " – " + (to == null ? "today" : f.format(to));
    }

    private static String label(TransactionType type) {
        return switch (type) {
            case EXPENSE -> "Expense";
            case INCOME -> "Income";
            case TRANSFER -> "Transfer";
            case LOAN_DISBURSEMENT -> "Loan received";
            case LOAN_PRINCIPAL -> "Loan repayment";
        };
    }

    private static String name(TransactionResponse.AccountInfo a) {
        return a == null ? null : a.name();
    }

    private static String category(TransactionResponse t) {
        if (t.category() == null) {
            return null;
        }
        return t.category().parentName() == null ? t.category().name() : t.category().parentName() + " › " + t.category().name();
    }

    private static String titleCase(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}
