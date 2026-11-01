package com.paisatrack.modules.attachment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A receipt file attached to a transaction. The bytes are in {@link AttachmentStorage} under {@code storageKey}. */
@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "file_name", nullable = false, length = 200, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 200, updatable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Attachment() {
    }

    Attachment(UUID userId, UUID transactionId, String fileName, String contentType, long sizeBytes, String sha256) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.transactionId = transactionId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = userId + "/" + id;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
