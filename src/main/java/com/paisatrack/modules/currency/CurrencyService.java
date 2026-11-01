package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.currency.dto.CurrencyDtos.AddRateRequest;
import com.paisatrack.modules.currency.dto.CurrencyDtos.ConversionResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.CurrencyResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.RateResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.RefreshResponse;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.platform.validation.SupportedCurrencies;

/**
 * Currency reference data, exchange rates and conversion. Rates are never hardcoded: they are
 * entered by the user or fetched from an optional provider. Conversions keep the original amount,
 * currency, rate, rate date and source; when no rate exists the result says so instead of guessing.
 */
@Service
public class CurrencyService {

    /** Provider bases to cross through (ECB rates are quoted against EUR). */
    static final Set<String> CROSS_BASES = Set.of("EUR");
    private static final int MAX_LISTED = 200;
    private static final Logger log = LoggerFactory.getLogger(CurrencyService.class);

    private final ExchangeRateRepository rates;
    private final UserPreferencesService preferences;
    private final ObjectProvider<ExchangeRateProvider> provider;

    CurrencyService(ExchangeRateRepository rates, UserPreferencesService preferences, ObjectProvider<ExchangeRateProvider> provider) {
        this.rates = rates;
        this.preferences = preferences;
        this.provider = provider;
    }

    /** Currencies in use today, for pickers. Withdrawn ones (French franc, old Afghan afghani...) are left out of the list. */
    public List<CurrencyResponse> currencies() {
        return SupportedCurrencies.codes().stream().sorted()
                .filter(code -> !WITHDRAWN.contains(code))
                .map(code -> {
                    Currency c = Currency.getInstance(code);
                    return new CurrencyResponse(code, c.getDisplayName(Locale.ENGLISH), c.getSymbol(Locale.forLanguageTag("en-IN")),
                            Math.max(0, c.getDefaultFractionDigits()));
                })
                .filter(c -> !c.name().matches(".*\\(\\d{4}.*"))
                .toList();
    }

    /** ISO codes the JDK still knows but that are no longer legal tender. */
    private static final Set<String> WITHDRAWN = Set.of("ADP", "AFA", "ATS", "AYM", "AZM", "BEF", "BGL", "BYB", "BYR", "CSD", "CUC",
            "CYP", "DEM", "EEK", "ESP", "FIM", "FRF", "GHC", "GRD", "GWP", "HRK", "IEP", "ITL", "LTL", "LUF", "LVL", "MGF", "MRO",
            "MTL", "MZM", "NLG", "PTE", "ROL", "RUR", "SDD", "SIT", "SKK", "SLL", "SRG", "STD", "TMM", "TPE", "TRL", "VEB", "VEF",
            "XFO", "XFU", "YUM", "ZMK", "ZWD", "ZWL", "ZWN", "ZWR");

    @Transactional(readOnly = true)
    public List<RateResponse> rates(UUID userId, String currency) {
        return rates.findRecent(userId, currency, PageRequest.of(0, MAX_LISTED)).stream().map(CurrencyService::toResponse).toList();
    }

    /** Adds the user's own rate for a pair and day; entering the same pair and day again replaces it. */
    @Transactional
    public RateResponse addRate(UUID userId, AddRateRequest r) {
        if (r.base().equals(r.quote())) {
            throw new FieldValidationException("quote", "must differ from base");
        }
        if (r.rate().signum() <= 0) {
            throw new FieldValidationException("rate", "must be greater than zero");
        }
        if (r.rate().scale() > 10 || r.rate().precision() - r.rate().scale() > 14) {
            throw new FieldValidationException("rate", "has too many digits");
        }
        LocalDate today = preferences.today(userId);
        LocalDate date = r.date() != null ? r.date() : today;
        if (date.isAfter(today)) {
            throw new FieldValidationException("date", "can't be in the future");
        }
        ExchangeRate rate = rates.findByUserIdAndBaseAndQuoteAndRateDate(userId, r.base(), r.quote(), date)
                .orElseGet(() -> new ExchangeRate(userId, r.base(), r.quote(), r.rate(), date, ExchangeRate.MANUAL));
        rate.setRate(r.rate());
        rates.saveAndFlush(rate);
        log.info("Exchange rate saved pair={}{} date={}", r.base(), r.quote(), date);
        return toResponse(rate);
    }

    @Transactional
    public void deleteRate(UUID userId, UUID rateId) {
        ExchangeRate rate = rates.findByIdAndUserId(rateId, userId).orElseThrow(() -> new ResourceNotFoundException("Exchange rate"));
        rates.delete(rate);
    }

    /** One amount, with the rate used; unavailable (not an error) when there is no rate. */
    @Transactional(readOnly = true)
    public ConversionResponse convert(UUID userId, BigDecimal amount, String from, String to, LocalDate date) {
        MoneyRules.requireValidAmount("amount", amount.abs().max(new BigDecimal("0.0001")), from);
        String target = to != null ? to : preferences.defaultCurrency(userId);
        LocalDate day = date != null ? date : preferences.today(userId);
        RateBook book = book(userId, target, List.of(from), day);
        Optional<RateBook.Quote> q = book.quote(from, day);
        return q.map(x -> new ConversionResponse(amount, from, x.rate().setScale(10, RoundingMode.HALF_EVEN).stripTrailingZeros(),
                        book.convert(amount, from, day).orElseThrow(), target, x.rateDate(), x.source(), true, null))
                .orElseGet(() -> new ConversionResponse(amount, from, null, null, target, null, null, false,
                        "No " + from + " to " + target + " rate on or before " + day + ". Add one, or turn on the rate provider."));
    }

    /** Everything needed to convert these currencies into {@code target} for dates up to {@code upTo}. */
    @Transactional(readOnly = true)
    public RateBook book(UUID userId, String target, Collection<String> currencies, LocalDate upTo) {
        Set<String> involved = new HashSet<>(currencies);
        involved.add(target);
        involved.addAll(CROSS_BASES);
        return new RateBook(target, rates.findUsable(userId, involved, upTo));
    }

    public boolean providerEnabled() {
        return provider.getIfAvailable() != null;
    }

    /** Fetches and stores the provider's latest rates. Repeating it on the same day just updates them. */
    @Transactional
    public RefreshResponse refresh() {
        ExchangeRateProvider p = provider.getIfAvailable();
        if (p == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "No rate provider is configured. Add rates yourself, or set paisatrack.fx.provider=frankfurter.");
        }
        ExchangeRateProvider.Rates latest = p.latest();
        int stored = 0;
        for (Map.Entry<String, BigDecimal> e : latest.rates().entrySet()) {
            String quote = e.getKey();
            if (quote.equals(latest.base()) || !SupportedCurrencies.isSupported(quote) || e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            stored += rates.upsertProviderRate(latest.base(), quote, e.getValue(), latest.date(), p.name());
        }
        log.info("Exchange rates refreshed provider={} date={} stored={}", p.name(), latest.date(), stored);
        return new RefreshResponse(p.name(), latest.date(), stored);
    }

    private static RateResponse toResponse(ExchangeRate r) {
        return new RateResponse(r.getId(), r.getBase(), r.getQuote(), r.getRate().stripTrailingZeros(), r.getRateDate(), r.getSource(),
                r.isManual());
    }
}
