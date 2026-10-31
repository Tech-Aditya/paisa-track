package com.paisatrack.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    List<Account> findByUserIdOrderByCreatedAt(UUID userId);

    List<Account> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    Optional<Account> findByUserIdAndDefaultAccountTrue(UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Account a set a.defaultAccount = false where a.userId = :userId and a.defaultAccount = true")
    void clearDefault(@Param("userId") UUID userId);
}
