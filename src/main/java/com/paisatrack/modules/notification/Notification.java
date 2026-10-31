package com.paisatrack.modules.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An in-app notification. Created by SQL (insert ... on conflict do nothing on the dedupe key). */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 10, updatable = false)
    private NotificationStage stage;

    @Column(name = "title", nullable = false, length = 120, updatable = false)
    private String title;

    @Column(name = "message", nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "link", length = 100, updatable = false)
    private String link;

    @Column(name = "dedupe_key", nullable = false, length = 200, updatable = false)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationStage getStage() {
        return stage;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getLink() {
        return link;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
