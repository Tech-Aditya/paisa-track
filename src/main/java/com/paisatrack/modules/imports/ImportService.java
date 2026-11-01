package com.paisatrack.modules.imports;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountRef;
import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.imports.dto.ImportCommitRequest;
import com.paisatrack.modules.imports.dto.ImportMapping;
import com.paisatrack.modules.imports.dto.ImportPreviewResponse;
import com.paisatrack.modules.imports.dto.ImportResult;
import com.paisatrack.modules.transaction.CategorySuggestionService;
import com.paisatrack.modules.transaction.TransactionService;
import com.paisatrack.modules.transaction.TransactionType;
import com.paisatrack.modules.transaction.dto.ExpenseRequest;
import com.paisatrack.modules.transaction.dto.IncomeRequest;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.money.MoneyRules;

/**
 * Bank statement import in two steps. Preview reads the file and shows every row with a suggested
 * category; rows that look already recorded (same amount on the account within two days, or a
 * repeat inside the file) are flagged and left unticked. Commit saves only the rows the user kept,
 * all in one database transaction, as ordinary expenses and income.
 */
@Service
public class ImportService {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_ROWS = 2000;
    static final int DUPLICATE_DAYS = 2;
    private static final DateTimeFormatter SHOWN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final AccountService accounts;
    private final CategorySuggestionService suggestions;
    private final TransactionService transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    ImportService(AccountService accounts, CategorySuggestionService suggestions, TransactionService transactions,
                  NamedParameterJdbcTemplate jdbc, IdempotencyService idempotency, AuditService audit) {
        this.accounts = accounts;
        this.suggestions = suggestions;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ImportPreviewResponse preview(UUID userId, UUID accountId, String fileName, byte[] bytes, ImportMapping requested) {
        AccountRef account = activeAccount(userId, accountId);
        if (bytes == null || bytes.length == 0) {
            throw new FieldValidationException("file", "is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new FieldValidationException("file", "is larger than 2 MB");
        }
        String text = StatementParser.decode(bytes);
        if (text.indexOf('\0') >= 0) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "This doesn't look like a CSV file. Export the statement as CSV and try again");
        }
        List<List<String>> all = StatementParser.rows(text, StatementParser.delimiter(text));
        ImportMapping mapping = requested == null || requested.isEmpty() ? StatementParser.detect(all) : requested;
        if (mapping == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Couldn't find the date and amount columns. The file needs a header row such as Date, Narration, Withdrawal, Deposit");
        }
        int headerRow = mapping.headerRow() == null ? 0 : mapping.headerRow();
        if (headerRow < 0 || headerRow >= all.size()) {
            throw new FieldValidationException("headerRow", "is outside the file");
        }
        List<String> headers = all.get(headerRow);
        requireColumn("dateColumn", mapping.dateColumn(), headers);
        boolean split = mapping.debitColumn() != null || mapping.creditColumn() != null;
        if (split) {
            requireColumn("debitColumn", mapping.debitColumn(), headers);
            requireColumn("creditColumn", mapping.creditColumn(), headers);
        } else {
            requireColumn("amountColumn", mapping.amountColumn(), headers);
        }
        optionalColumn("descriptionColumn", mapping.descriptionColumn(), headers);
        optionalColumn("typeColumn", mapping.typeColumn(), headers);

        List<Integer> data = new ArrayList<>();
        for (int i = headerRow + 1; i < all.size(); i++) {
            if (all.get(i).stream().anyMatch(c -> !c.isBlank())) {
                data.add(i);
            }
        }
        ImportMapping m0 = mapping;
        // Banks add footers ("Opening balance", "** End of statement **"): only rows with a date count.
        if (mapping.dateFormat() == null || mapping.dateFormat().isBlank()) {
            List<String> samples = data.stream().map(i -> cell(all.get(i), m0.dateColumn())).filter(StatementParser::looksLikeDate).limit(200).toList();
            String detected = StatementParser.dateFormat(samples);
            if (detected == null) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Couldn't read the dates. Pick the date format and try again");
            }
            mapping = mapping.withDateFormat(detected);
        } else if (!StatementParser.DATE_FORMATS.contains(mapping.dateFormat())) {
            throw new FieldValidationException("dateFormat", "must be one of " + String.join(", ", StatementParser.DATE_FORMATS));
        }
        DateTimeFormatter fmt = StatementParser.formatter(mapping.dateFormat());

        List<Parsed> parsed = new ArrayList<>();
        for (int i : data) {
            List<String> r = all.get(i);
            String rawDate = cell(r, mapping.dateColumn());
            if (!StatementParser.looksLikeDate(rawDate)) {
                continue;
            }
            parsed.add(parse(r, i + 1, rawDate, fmt, mapping, split, account.currency()));
            if (parsed.size() > MAX_ROWS) {
                throw new FieldValidationException("file", "has more than " + MAX_ROWS + " transactions; split it into smaller files");
            }
        }
        if (parsed.isEmpty()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "No transactions found below the header row");
        }

        List<Existing> existing = existing(userId, accountId, parsed);
        Set<UUID> matched = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        CategorySuggestionService.Matcher expenseMatcher = suggestions.matcher(userId, TransactionType.EXPENSE);
        CategorySuggestionService.Matcher incomeMatcher = suggestions.matcher(userId, TransactionType.INCOME);

        List<ImportPreviewResponse.Row> rows = new ArrayList<>();
        int duplicates = 0;
        int errors = 0;
        int selected = 0;
        for (Parsed p : parsed) {
            if (p.error() != null) {
                errors++;
                rows.add(new ImportPreviewResponse.Row(p.line(), p.date(), p.type(), p.amount(), p.description(),
                        null, null, null, null, null, false, p.error()));
                continue;
            }
            String reason = null;
            UUID duplicateOf = null;
            Existing match = existing.stream()
                    .filter(e -> !matched.contains(e.id()) && e.inflow() == (p.type() == TransactionType.INCOME)
                            && e.amount().compareTo(p.amount()) == 0
                            && Math.abs(ChronoUnit.DAYS.between(e.date(), p.date())) <= DUPLICATE_DAYS)
                    .min((a, b) -> Long.compare(Math.abs(ChronoUnit.DAYS.between(a.date(), p.date())), Math.abs(ChronoUnit.DAYS.between(b.date(), p.date()))))
                    .orElse(null);
            String key = p.date() + "|" + p.type() + "|" + p.amount().toPlainString() + "|" + normalise(p.description());
            Integer earlier = seen.putIfAbsent(key, p.line());
            if (match != null) {
                matched.add(match.id());
                duplicateOf = match.id();
                reason = "Already recorded on " + match.date().format(SHOWN_DATE) + (match.label() == null || match.label().isBlank() ? "" : " (" + match.label() + ")");
            } else if (earlier != null) {
                reason = "Same as line " + earlier + " in this file";
            }
            CategorySuggestionService.Matcher matcher = p.type() == TransactionType.INCOME ? incomeMatcher : expenseMatcher;
            CategorySuggestionService.Suggestion s = matcher.best(p.description()).orElse(null);
            boolean tick = reason == null;
            duplicates += reason == null ? 0 : 1;
            selected += tick ? 1 : 0;
            rows.add(new ImportPreviewResponse.Row(p.line(), p.date(), p.type(), p.amount(), p.description(),
                    s == null ? null : s.categoryId(), s == null ? null : label(s), s == null ? null : s.reason(),
                    reason, duplicateOf, tick, null));
        }
        return new ImportPreviewResponse(cleanName(fileName), account.id(), account.name(), account.currency(), headers, mapping,
                StatementParser.DATE_FORMATS, rows.size(), selected, duplicates, errors, rows);
    }

    @Transactional
    public ImportResult commit(UUID userId, ImportCommitRequest request, String idempotencyKey) {
        AccountRef account = activeAccount(userId, request.accountId());
        String fileName = cleanName(request.fileName());
        UUID batchId = idempotency.execute(userId, idempotencyKey, "import.commit", request, () -> {
            String notes = "Imported from " + (fileName == null ? "a bank statement" : fileName);
            List<ImportCommitRequest.Item> items = request.items();
            for (int i = 0; i < items.size(); i++) {
                ImportCommitRequest.Item item = items.get(i);
                String field = "items[" + i + "]";
                try {
                    MoneyRules.requireValidAmount(field + ".amount", item.amount(), account.currency());
                    String description = item.description() == null ? null : item.description().strip();
                    if (item.type() == TransactionType.EXPENSE) {
                        transactions.createExpense(userId, new ExpenseRequest(item.amount(), item.categoryId(), account.id(), null,
                                item.date(), null, description, null, null, notes), null);
                    } else if (item.type() == TransactionType.INCOME) {
                        transactions.createIncome(userId, new IncomeRequest(item.amount(), item.categoryId(), account.id(), null,
                                item.date(), null, description, null, null, notes, null, null), null);
                    } else {
                        throw new FieldValidationException(field + ".type", "must be EXPENSE or INCOME");
                    }
                } catch (FieldValidationException e) {
                    throw new FieldValidationException(e.getField().startsWith(field) ? e.getField() : field + "." + e.getField(),
                            e.getMessage() + " (row " + (i + 1) + ")");
                } catch (ApiException e) {
                    throw new ApiException(e.getErrorCode(), "Row " + (i + 1) + ": " + e.getMessage());
                }
            }
            UUID id = jdbc.queryForObject("""
                    insert into import_batches (user_id, account_id, file_name, rows_imported)
                    values (:userId, :accountId, :fileName, :rows) returning id""",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("accountId", account.id())
                            .addValue("fileName", fileName).addValue("rows", items.size()), UUID.class);
            audit.record(userId, "ACCOUNT", account.id(), AuditAction.UPDATED,
                    Map.of("imported", items.size(), "file", fileName == null ? "" : fileName));
            log.info("Statement imported batchId={} rows={}", id, items.size());
            return id;
        });
        return jdbc.queryForObject("""
                select id, account_id, file_name, rows_imported, created_at from import_batches where id = :id and user_id = :userId""",
                new MapSqlParameterSource().addValue("id", batchId).addValue("userId", userId),
                (rs, row) -> new ImportResult(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        rs.getTimestamp(5).toInstant()));
    }

    @Transactional(readOnly = true)
    public List<ImportResult> recent(UUID userId) {
        return jdbc.query("""
                select id, account_id, file_name, rows_imported, created_at from import_batches
                where user_id = :userId order by created_at desc limit 10""",
                new MapSqlParameterSource("userId", userId),
                (rs, row) -> new ImportResult(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        toInstant(rs.getTimestamp(5))));
    }

    // ---- helpers ----

    private record Parsed(int line, LocalDate date, TransactionType type, BigDecimal amount, String description, String error) {
    }

    /** Something already on the account: money in or out, its amount and date. */
    private record Existing(UUID id, LocalDate date, boolean inflow, BigDecimal amount, String label) {
    }

    private Parsed parse(List<String> r, int line, String rawDate, DateTimeFormatter fmt, ImportMapping m, boolean split, String currency) {
        String description = clip(cell(r, m.descriptionColumn()));
        LocalDate date = StatementParser.parseDate(rawDate, fmt);
        if (date == null) {
            return new Parsed(line, null, null, null, description, "Can't read the date '" + clip(rawDate) + "'");
        }
        try {
            BigDecimal amount;
            TransactionType type;
            if (split) {
                BigDecimal debit = StatementParser.parseAmount(cell(r, m.debitColumn()));
                BigDecimal credit = StatementParser.parseAmount(cell(r, m.creditColumn()));
                boolean hasDebit = debit != null && debit.signum() != 0;
                boolean hasCredit = credit != null && credit.signum() != 0;
                if (hasDebit == hasCredit) {
                    return new Parsed(line, date, null, null, description,
                            hasDebit ? "Has both a withdrawal and a deposit" : "No amount");
                }
                amount = (hasDebit ? debit : credit).abs();
                type = hasDebit ? TransactionType.EXPENSE : TransactionType.INCOME;
            } else {
                BigDecimal v = StatementParser.parseAmount(cell(r, m.amountColumn()));
                if (v == null || v.signum() == 0) {
                    return new Parsed(line, date, null, null, description, "No amount");
                }
                String t = cell(r, m.typeColumn()).toLowerCase(Locale.ROOT);
                boolean out = t.isEmpty() ? v.signum() < 0 : t.startsWith("d") || t.startsWith("w");
                amount = v.abs();
                type = out ? TransactionType.EXPENSE : TransactionType.INCOME;
            }
            if (amount.compareTo(MoneyRules.MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > MoneyRules.fractionDigits(currency)) {
                return new Parsed(line, date, type, null, description, "Amount isn't valid for " + currency);
            }
            return new Parsed(line, date, type, MoneyRules.scaled(amount, currency), description, null);
        } catch (NumberFormatException e) {
            return new Parsed(line, date, null, null, description, "Can't read the amount '" + clip(e.getMessage()) + "'");
        }
    }

    private List<Existing> existing(UUID userId, UUID accountId, List<Parsed> parsed) {
        LocalDate from = parsed.stream().map(Parsed::date).filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate to = parsed.stream().map(Parsed::date).filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
        if (from == null) {
            return List.of();
        }
        return jdbc.query("""
                select t.id, t.transaction_date, t.type in ('INCOME', 'LOAN_DISBURSEMENT'), t.amount,
                       coalesce(nullif(t.counterparty, ''), t.description)
                from transactions t
                where t.user_id = :userId and t.account_id = :accountId and t.status = 'ACTIVE' and t.transaction_date between :from and :to
                union all
                select t.id, t.transaction_date, true, coalesce(t.to_amount, t.amount), coalesce(nullif(t.counterparty, ''), t.description)
                from transactions t
                where t.user_id = :userId and t.to_account_id = :accountId and t.type = 'TRANSFER' and t.status = 'ACTIVE'
                  and t.transaction_date between :from and :to""",
                new MapSqlParameterSource().addValue("userId", userId).addValue("accountId", accountId)
                        .addValue("from", from.minusDays(DUPLICATE_DAYS)).addValue("to", to.plusDays(DUPLICATE_DAYS)),
                (rs, row) -> new Existing(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getBoolean(3),
                        rs.getBigDecimal(4).stripTrailingZeros(), rs.getString(5)));
    }

    private AccountRef activeAccount(UUID userId, UUID accountId) {
        if (accountId == null) {
            throw new FieldValidationException("accountId", "is required");
        }
        AccountRef account = accounts.require(userId, accountId);
        if (!account.active()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Account '" + account.name() + "' is deactivated");
        }
        return account;
    }

    private static void requireColumn(String field, Integer column, List<String> headers) {
        if (column == null) {
            throw new FieldValidationException(field, "is required");
        }
        optionalColumn(field, column, headers);
    }

    private static void optionalColumn(String field, Integer column, List<String> headers) {
        if (column != null && (column < 0 || column >= Math.max(headers.size(), 1) + 20)) {
            throw new FieldValidationException(field, "is not a column in the file");
        }
    }

    private static String cell(List<String> row, Integer column) {
        return column == null || column < 0 || column >= row.size() ? "" : row.get(column);
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll(".*[/\\\\]", "").replaceAll("[\\p{Cntrl}]", "").strip();
        return n.isEmpty() ? null : n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static String label(CategorySuggestionService.Suggestion s) {
        return s.parentName() == null ? s.name() : s.parentName() + " › " + s.name();
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
