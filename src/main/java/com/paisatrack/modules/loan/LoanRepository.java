package com.paisatrack.modules.loan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoanRepository extends JpaRepository<Loan, UUID> {

    @Query("select l from Loan l where l.id = :id and l.userId = :userId and l.deletedAt is null")
    Optional<Loan> findActive(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Row lock: payments, prepayments and voids of one loan run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id and l.userId = :userId and l.deletedAt is null")
    Optional<Loan> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select l from Loan l where l.userId = :userId and l.deletedAt is null order by l.startDate desc, l.createdAt desc")
    List<Loan> findAllActive(@Param("userId") UUID userId);

    boolean existsByUserIdAndInterestCategoryId(UUID userId, UUID categoryId);
}
