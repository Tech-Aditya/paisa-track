package com.paisatrack.modules.attachment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByUserIdAndTransactionIdOrderByCreatedAtAsc(UUID userId, UUID transactionId);

    Optional<Attachment> findByIdAndUserId(UUID id, UUID userId);

    long countByTransactionId(UUID transactionId);

    @Query("select a.transactionId, count(a) from Attachment a where a.userId = :userId and a.transactionId in :ids group by a.transactionId")
    List<Object[]> countFor(@Param("userId") UUID userId, @Param("ids") Collection<UUID> transactionIds);
}
