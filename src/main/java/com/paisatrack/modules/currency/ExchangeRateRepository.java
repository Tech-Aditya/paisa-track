package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /** The user's own rates plus shared provider rates touching these currencies, up to a date. */
    @Query("""
            select r from ExchangeRate r
            where (r.userId = :userId or r.userId is null) and r.rateDate <= :upTo
              and r.base in :currencies and r.quote in :currencies
            order by r.rateDate""")
    List<ExchangeRate> findUsable(@Param("userId") UUID userId, @Param("currencies") Collection<String> currencies,
                                  @Param("upTo") LocalDate upTo);

    @Query("""
            select r from ExchangeRate r where (r.userId = :userId or r.userId is null)
              and (:currency is null or r.base = :currency or r.quote = :currency)
            order by r.rateDate desc, r.createdAt desc""")
    List<ExchangeRate> findRecent(@Param("userId") UUID userId, @Param("currency") String currency, Pageable page);

    Optional<ExchangeRate> findByIdAndUserId(UUID id, UUID userId);

    Optional<ExchangeRate> findByUserIdAndBaseAndQuoteAndRateDate(UUID userId, String base, String quote, LocalDate rateDate);

    /** Idempotent: a provider rate for a pair and day is stored once; a second fetch updates it. */
    @Modifying
    @Query(value = """
            insert into exchange_rates (base, quote, rate, rate_date, source)
            values (:base, :quote, :rate, :rateDate, :source)
            on conflict (source, base, quote, rate_date) where user_id is null do update set rate = excluded.rate""",
            nativeQuery = true)
    int upsertProviderRate(@Param("base") String base, @Param("quote") String quote, @Param("rate") BigDecimal rate,
                           @Param("rateDate") LocalDate rateDate, @Param("source") String source);
}
