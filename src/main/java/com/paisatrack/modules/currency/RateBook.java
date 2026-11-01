package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import com.paisatrack.platform.money.MoneyRules;

/**
 * Converts amounts into one target currency using the latest rate on or before each date. Manual
 * rates win over provider rates of the same day; provider rates may be crossed through their base
 * (EUR). Keeps a record of what it converted and what it couldn't, so totals are never silently
 * mixed or dropped.
 */
public final class RateBook {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    /** A rate actually used: how many target units one source unit buys, from which day and source. */
    public record Quote(BigDecimal rate, LocalDate rateDate, String source) {
    }

    /** Per source currency: how much was converted, with which rates, and what was left out. */
    public record Usage(String currency, long converted, long missing, LocalDate firstRateDate, LocalDate lastRateDate,
                        java.util.Set<String> sources, LocalDate missingFrom) {
    }

    private record Point(BigDecimal rate, boolean manual, String source) {
    }

    private final String target;
    private final Map<String, TreeMap<LocalDate, Point>> manual = new HashMap<>();
    private final Map<String, TreeMap<LocalDate, Point>> provider = new HashMap<>();
    private final Map<String, Tracker> usage = new TreeMap<>();

    RateBook(String target, List<ExchangeRate> rates) {
        this.target = target;
        for (ExchangeRate r : rates) {
            Map<String, TreeMap<LocalDate, Point>> into = r.isManual() ? manual : provider;
            into.computeIfAbsent(key(r.getBase(), r.getQuote()), k -> new TreeMap<>())
                    .put(r.getRateDate(), new Point(r.getRate(), r.isManual(), r.getSource()));
        }
    }

    private RateBook(RateBook source) {
        this.target = source.target;
        this.manual.putAll(source.manual);
        this.provider.putAll(source.provider);
    }

    /** The same rates with an empty usage record, e.g. for a comparison period that isn't reported on. */
    public RateBook untracked() {
        return new RateBook(this);
    }

    public String target() {
        return target;
    }

    /** The rate for {@code entries} transactions in {@code from} on {@code date}, recorded as used or missing. */
    public Optional<BigDecimal> use(String from, LocalDate date, long entries) {
        Optional<Quote> q = quote(from, date);
        if (!from.equals(target)) {
            usage.computeIfAbsent(from, Tracker::new).record(q.orElse(null), date, entries);
        }
        return q.map(Quote::rate);
    }

    /** The rate to convert {@code from} into the target on {@code date}, if one is known. */
    public Optional<Quote> quote(String from, LocalDate date) {
        if (from.equals(target)) {
            return Optional.of(new Quote(BigDecimal.ONE, date, "SAME_CURRENCY"));
        }
        List<Quote> candidates = new ArrayList<>();
        direct(manual, from, target, date).ifPresent(candidates::add);
        direct(provider, from, target, date).ifPresent(candidates::add);
        crossed(from, date).ifPresent(candidates::add);
        // Latest day wins; on the same day a manual rate wins (it's listed first).
        return candidates.stream().reduce((a, b) -> b.rateDate().isAfter(a.rateDate()) ? b : a);
    }

    /** {@code amount} in the target currency, rounded to its precision; empty when no rate exists. Recorded either way. */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, LocalDate date) {
        return convert(amount, from, date, 1);
    }

    /** Same, counting {@code entries} transactions behind the amount. */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, LocalDate date, long entries) {
        Optional<Quote> q = quote(from, date);
        if (!from.equals(target)) {
            usage.computeIfAbsent(from, Tracker::new).record(q.orElse(null), date, entries);
        }
        return q.map(x -> MoneyRules.scaled(amount.multiply(x.rate(), MC), target));
    }

    /** What happened to each other currency so far. */
    public List<Usage> usage() {
        return usage.values().stream().map(Tracker::toUsage).toList();
    }

    public boolean hasMissing() {
        return usage.values().stream().anyMatch(t -> t.missing > 0);
    }

    private static Optional<Quote> direct(Map<String, TreeMap<LocalDate, Point>> book, String from, String to, LocalDate date) {
        Optional<Quote> straight = floor(book, from, to, date).map(e -> new Quote(e.getValue().rate(), e.getKey(), e.getValue().source()));
        Optional<Quote> inverse = floor(book, to, from, date)
                .map(e -> new Quote(BigDecimal.ONE.divide(e.getValue().rate(), MC), e.getKey(), e.getValue().source()));
        if (straight.isPresent() && inverse.isPresent()) {
            return inverse.get().rateDate().isAfter(straight.get().rateDate()) ? inverse : straight;
        }
        return straight.isPresent() ? straight : inverse;
    }

    /** Through a provider's base: from -> base -> target, each the latest on or before the date. */
    private Optional<Quote> crossed(String from, LocalDate date) {
        Optional<Quote> best = Optional.empty();
        for (String pair : provider.keySet()) {
            String base = pair.substring(0, 3);
            if (base.equals(from) || base.equals(target)) {
                continue;
            }
            var toFrom = floor(provider, base, from, date);
            var toTarget = floor(provider, base, target, date);
            if (toFrom.isEmpty() || toTarget.isEmpty()) {
                continue;
            }
            LocalDate day = toFrom.get().getKey().isBefore(toTarget.get().getKey()) ? toFrom.get().getKey() : toTarget.get().getKey();
            Quote q = new Quote(toTarget.get().getValue().rate().divide(toFrom.get().getValue().rate(), MC), day,
                    toTarget.get().getValue().source());
            if (best.isEmpty() || q.rateDate().isAfter(best.get().rateDate())) {
                best = Optional.of(q);
            }
        }
        return best;
    }

    private static Optional<Map.Entry<LocalDate, Point>> floor(Map<String, TreeMap<LocalDate, Point>> book, String base, String quote,
                                                              LocalDate date) {
        TreeMap<LocalDate, Point> series = book.get(key(base, quote));
        return series == null ? Optional.empty() : Optional.ofNullable(series.floorEntry(date));
    }

    private static String key(String base, String quote) {
        return base + quote;
    }

    private static final class Tracker {
        private final String currency;
        private long converted;
        private long missing;
        private LocalDate first;
        private LocalDate last;
        private LocalDate missingFrom;
        private final TreeSet<String> sources = new TreeSet<>();

        Tracker(String currency) {
            this.currency = currency;
        }

        void record(Quote q, LocalDate date, long entries) {
            if (q == null) {
                missing += entries;
                if (missingFrom == null || date.isBefore(missingFrom)) {
                    missingFrom = date;
                }
                return;
            }
            converted += entries;
            sources.add(q.source());
            if (first == null || q.rateDate().isBefore(first)) {
                first = q.rateDate();
            }
            if (last == null || q.rateDate().isAfter(last)) {
                last = q.rateDate();
            }
        }

        Usage toUsage() {
            return new Usage(currency, converted, missing, first, last, java.util.Set.copyOf(sources), missingFrom);
        }
    }
}
