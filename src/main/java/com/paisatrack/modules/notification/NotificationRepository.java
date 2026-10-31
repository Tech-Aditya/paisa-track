package com.paisatrack.modules.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Returns 0 when this reminder already exists, so repeated or parallel runs never duplicate it. */
    @Modifying
    @Query(value = """
            insert into notifications (user_id, type, stage, title, message, subject_id, due_date, link, dedupe_key)
            values (:userId, :type, :stage, :title, :message, :subjectId, :dueDate, :link, :dedupeKey)
            on conflict (user_id, dedupe_key) do nothing""", nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("type") String type, @Param("stage") String stage,
                       @Param("title") String title, @Param("message") String message, @Param("subjectId") UUID subjectId,
                       @Param("dueDate") LocalDate dueDate, @Param("link") String link, @Param("dedupeKey") String dedupeKey);

    @Query("""
            select n from Notification n where n.userId = :userId and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc, n.id""")
    Page<Notification> findForUser(@Param("userId") UUID userId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("at") Instant at);
}
