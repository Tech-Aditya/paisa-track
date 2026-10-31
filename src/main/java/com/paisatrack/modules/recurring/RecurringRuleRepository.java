package com.paisatrack.modules.recurring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {

    Optional<RecurringRule> findByIdAndUserId(UUID id, UUID userId);

    /** Row lock: edits, status changes and occurrence generation of one rule run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurringRule r where r.id = :id and r.userId = :userId")
    Optional<RecurringRule> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurringRule r where r.userId = :userId and r.status = com.paisatrack.modules.recurring.RuleStatus.ACTIVE order by r.id")
    List<RecurringRule> findActiveForUpdate(@Param("userId") UUID userId);

    @Query("select r from RecurringRule r where r.userId = :userId order by r.name")
    List<RecurringRule> findAllByUser(@Param("userId") UUID userId);

    @Query("select distinct r.userId from RecurringRule r where r.status = com.paisatrack.modules.recurring.RuleStatus.ACTIVE")
    List<UUID> findUsersWithActiveRules();

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
