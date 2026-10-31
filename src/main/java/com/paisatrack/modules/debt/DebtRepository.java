package com.paisatrack.modules.debt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DebtRepository extends JpaRepository<Debt, UUID> {

    @Query("select d from Debt d where d.id = :id and d.userId = :userId and d.deletedAt is null")
    Optional<Debt> findActive(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Row lock: repayments, write-offs and edits of one debt run one at a time, so outstanding can't go negative. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Debt d where d.id = :id and d.userId = :userId and d.deletedAt is null")
    Optional<Debt> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select d from Debt d where d.userId = :userId and d.deletedAt is null order by d.startDate desc, d.createdAt desc")
    List<Debt> findAllActive(@Param("userId") UUID userId);

    boolean existsByUserIdAndPersonId(UUID userId, UUID personId);
}
