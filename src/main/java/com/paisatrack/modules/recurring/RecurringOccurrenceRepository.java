package com.paisatrack.modules.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RecurringOccurrenceRepository extends JpaRepository<RecurringOccurrence, UUID> {

    /** Idempotent: a second insert for the same rule and date does nothing. Returns rows inserted. */
    @Modifying
    @Query(value = """
            insert into recurring_occurrences (user_id, rule_id, due_date, expected_amount)
            values (:userId, :ruleId, :dueDate, :amount)
            on conflict (rule_id, due_date) do nothing""", nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("ruleId") UUID ruleId, @Param("dueDate") LocalDate dueDate,
                       @Param("amount") BigDecimal amount);

    /** Drops still-expected occurrences from a date on, e.g. after a pause or schedule change. Overdue ones stay. */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from RecurringOccurrence o where o.ruleId = :ruleId and o.dueDate >= :from
            and o.status = com.paisatrack.modules.recurring.RecurringOccurrence.Status.EXPECTED""")
    int deleteExpectedFrom(@Param("ruleId") UUID ruleId, @Param("from") LocalDate from);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RecurringOccurrence o where o.id = :id and o.userId = :userId")
    Optional<RecurringOccurrence> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    List<RecurringOccurrence> findByRuleIdOrderByDueDateDesc(UUID ruleId);

    @Query("""
            select o from RecurringOccurrence o where o.userId = :userId and o.dueDate between :from and :to
            and (:status is null or o.status = :status) order by o.dueDate, o.id""")
    List<RecurringOccurrence> findInRange(@Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                          @Param("status") RecurringOccurrence.Status status);

    /** Expected (not yet confirmed or skipped) up to a date, overdue ones included. */
    @Query("""
            select o from RecurringOccurrence o where o.userId = :userId and o.dueDate <= :to
            and o.status = com.paisatrack.modules.recurring.RecurringOccurrence.Status.EXPECTED order by o.dueDate, o.id""")
    List<RecurringOccurrence> findExpectedUntil(@Param("userId") UUID userId, @Param("to") LocalDate to);

    @Query("""
            select o from RecurringOccurrence o where o.ruleId in :ruleIds
            and o.status = com.paisatrack.modules.recurring.RecurringOccurrence.Status.EXPECTED order by o.dueDate""")
    List<RecurringOccurrence> findExpectedByRules(@Param("ruleIds") Collection<UUID> ruleIds);

    Optional<RecurringOccurrence> findByUserIdAndTransactionId(UUID userId, UUID transactionId);

    @Query("""
            select count(o) > 0 from RecurringOccurrence o where o.ruleId = :ruleId
            and o.status = com.paisatrack.modules.recurring.RecurringOccurrence.Status.CONFIRMED""")
    boolean hasConfirmed(@Param("ruleId") UUID ruleId);
}
