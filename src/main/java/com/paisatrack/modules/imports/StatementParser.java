package com.paisatrack.modules.imports;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.paisatrack.modules.imports.dto.ImportMapping;

/**
 * Reads a bank statement exported as CSV: guesses the delimiter, finds the header row and the
 * date, narration and amount columns, and understands the usual Indian date and amount styles
 * (01/10/2026, 01-Oct-26, "1,23,456.00", "500.00 Dr", "(250)"). No database access here.
 */
final class StatementParser {

    /** Date styles we try, in this order (day first, as Indian banks write them). */
    static final List<String> DATE_FORMATS = List.of("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yy", "dd-MM-yy",
            "dd-MMM-yyyy", "dd MMM yyyy", "dd-MMM-yy", "dd MMM yy", "dd/MMM/yyyy", "d MMM yyyy", "MMM d, yyyy", "MM/dd/yyyy", "yyyy/MM/dd");

    private static final Pattern DATE_LIKE = Pattern.compile("^(\\d{1,4}[-/. ]\\S.*|[A-Za-z]{3,9}\\.? \\d{1,2},? \\d{2,4}.*)$");
    private static final Pattern TIME_SUFFIX = Pattern.compile("[ T]+\\d{1,2}:\\d{2}.*$");

    private StatementParser() {
    }

    /** UTF-8 (with or without BOM); falls back to Windows-1252 for old Excel exports. */
    static String decode(byte[] bytes) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** The delimiter that splits the first lines most consistently. */
    static char delimiter(String text) {
        String[] lines = text.lines().filter(l -> !l.isBlank()).limit(20).toArray(String[]::new);
        char best = ',';
        int bestScore = -1;
        for (char d : new char[] {',', ';', '\t', '|'}) {
            int score = 0;
            for (String l : lines) {
                int n = split(l, d).size();
                score += n > 1 ? n : 0;
            }
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }
        return best;
    }

    /** RFC 4180 rows: quoted fields may hold the delimiter, doubled quotes and line breaks. */
    static List<List<String>> rows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString().strip());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().strip());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString().strip());
            rows.add(row);
        }
        return rows;
    }

    private static List<String> split(String line, char d) {
        List<List<String>> r = rows(line, d);
        return r.isEmpty() ? List.of() : r.getFirst();
    }

    /**
     * Finds the header row (banks often put the account details above it) and maps its columns.
     * Returns null when no row looks like a header with a date and an amount.
     */
    static ImportMapping detect(List<List<String>> rows) {
        for (int r = 0; r < Math.min(rows.size(), 30); r++) {
            List<String> h = rows.get(r);
            Integer date = find(h, "txn date", "transaction date", "tran date", "value date", "date", "posting date");
            Integer desc = find(h, "narration", "description", "particulars", "details", "remarks", "transaction details", "merchant", "payee");
            Integer debit = find(h, "withdrawal amt", "withdrawal amount", "withdrawal", "withdrawals", "debit amount", "debit", "dr amount", "dr", "paid out", "money out");
            Integer credit = find(h, "deposit amt", "deposit amount", "deposit", "deposits", "credit amount", "credit", "cr amount", "cr", "paid in", "money in");
            Integer amount = find(h, "amount", "transaction amount", "amount (inr)", "amt", "inr");
            Integer type = find(h, "dr/cr", "cr/dr", "type", "debit/credit", "transaction type", "txn type");
            if (date != null && ((debit != null && credit != null) || amount != null)) {
                boolean split = debit != null && credit != null;
                return new ImportMapping(r, date, desc, split ? debit : null, split ? credit : null, split ? null : amount,
                        split ? null : type, null);
            }
        }
        return null;
    }

    /** Exact header match first, then a header that starts with the name. */
    private static Integer find(List<String> header, String... names) {
        for (String n : names) {
            for (int i = 0; i < header.size(); i++) {
                if (norm(header.get(i)).equals(n)) {
                    return i;
                }
            }
        }
        for (String n : names) {
            if (n.length() < 4) {
                continue;
            }
            for (int i = 0; i < header.size(); i++) {
                if (norm(header.get(i)).startsWith(n)) {
                    return i;
                }
            }
        }
        return null;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('.', ' ').replaceAll("\\s+", " ").strip();
    }

    /** The first date style that reads every non-empty date cell, or null. */
    static String dateFormat(List<String> samples) {
        for (String f : DATE_FORMATS) {
            DateTimeFormatter fmt = formatter(f);
            boolean all = !samples.isEmpty();
            for (String s : samples) {
                if (parseDate(s, fmt) == null) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return f;
            }
        }
        return null;
    }

    static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern.replace("yyyy", "uuuu").replace("yy", "uu"))
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Cheap check that skips footers like "Opening balance" or "Total". */
    static boolean looksLikeDate(String s) {
        return s != null && s.length() <= 40 && DATE_LIKE.matcher(s.strip()).matches();
    }

    static LocalDate parseDate(String raw, DateTimeFormatter fmt) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = TIME_SUFFIX.matcher(raw.strip()).replaceFirst("");
        try {
            LocalDate d = LocalDate.parse(s, fmt);
            // Two-digit years: 26 means 2026, never 1926 or 2126.
            return d.getYear() < 100 ? d.plusYears(2000) : d;
        } catch (DateTimeParseException e) {
            // "Sept" is how some banks write September.
            if (s.toLowerCase(Locale.ROOT).contains("sept")) {
                return parseDate(s.replaceAll("(?i)sept", "Sep"), fmt);
            }
            return null;
        }
    }

    /**
     * An amount as written in a statement, signed: "1,23,456.78" → 123456.78, "500 Dr" or "(500)"
     * or "-500" → -500, "500 Cr" → 500. Null for blanks and dashes; throws for anything else.
     */
    static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("-") || s.equals("--") || s.equals("NA") || s.equals("N/A")) {
            return null;
        }
        boolean negative = false;
        if (s.endsWith("DR")) {
            negative = true;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("CR")) {
            s = s.substring(0, s.length() - 2);
        }
        s = s.replace("₹", "").replace("INR", "").replace("RS.", "").replace("RS", "").replace(",", "").replace(" ", "").replace(" ", "");
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = !negative;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("-")) {
            negative = !negative;
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return null;
        }
        if (!s.matches("-?\\d+(\\.\\d+)?")) {
            throw new NumberFormatException(raw);
        }
        BigDecimal v = new BigDecimal(s);
        return negative ? v.negate() : v;
    }
}
