package com.paisatrack.modules.attachment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.paisatrack.modules.attachment.dto.AttachmentResponse;
import com.paisatrack.modules.transaction.AttachmentCounter;
import com.paisatrack.modules.transaction.TransactionQueryService;
import com.paisatrack.platform.audit.AuditAction;
import com.paisatrack.platform.audit.AuditService;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;

/**
 * Receipts on transactions. Only JPEG, PNG, WEBP and PDF are accepted, recognised by their
 * content (magic bytes), not by the name or the browser's claim. Up to 5 MB each and 5 per
 * transaction. Files are only served to their owner.
 */
@Service
public class AttachmentService implements AttachmentCounter {

    /** A file to send back. */
    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_PER_TRANSACTION = 5;
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TransactionQueryService transactions;
    private final AuditService audit;

    AttachmentService(AttachmentRepository attachments, AttachmentStorage storage, TransactionQueryService transactions, AuditService audit) {
        this.attachments = attachments;
        this.storage = storage;
        this.transactions = transactions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(UUID userId, UUID transactionId) {
        transactions.get(userId, transactionId); // 404 unless it's yours
        return attachments.findByUserIdAndTransactionIdOrderByCreatedAtAsc(userId, transactionId).stream().map(AttachmentService::toResponse).toList();
    }

    @Transactional
    public AttachmentResponse upload(UUID userId, UUID transactionId, String originalName, byte[] bytes) {
        transactions.get(userId, transactionId);
        if (bytes == null || bytes.length == 0) {
            throw new FieldValidationException("file", "is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new FieldValidationException("file", "is larger than 5 MB");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only JPG, PNG, WEBP images and PDF files can be attached");
        }
        if (attachments.countByTransactionId(transactionId) >= MAX_PER_TRANSACTION) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "A transaction can have at most " + MAX_PER_TRANSACTION + " attachments");
        }
        Attachment a = new Attachment(userId, transactionId, safeName(originalName, type), type, bytes.length, sha256(bytes));
        storage.put(a.getStorageKey(), bytes);
        // If the database write fails, don't leave an orphaned file behind.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storage.delete(a.getStorageKey());
                    }
                }
            });
        }
        attachments.saveAndFlush(a);
        audit.record(userId, "TRANSACTION", transactionId, AuditAction.UPDATED, Map.of("attachmentAdded", a.getFileName()));
        log.info("Attachment added attachmentId={} type={} bytes={}", a.getId(), type, bytes.length);
        return toResponse(attachments.findById(a.getId()).orElse(a));
    }

    @Transactional(readOnly = true)
    public Content content(UUID userId, UUID attachmentId) {
        Attachment a = load(userId, attachmentId);
        return new Content(a.getFileName(), a.getContentType(), storage.get(a.getStorageKey()));
    }

    @Transactional
    public void delete(UUID userId, UUID attachmentId) {
        Attachment a = load(userId, attachmentId);
        attachments.delete(a);
        attachments.flush();
        audit.record(userId, "TRANSACTION", a.getTransactionId(), AuditAction.UPDATED, Map.of("attachmentRemoved", a.getFileName()));
        // The file goes only once the row is gone for good.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(a.getStorageKey());
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> counts(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, Integer> out = new HashMap<>();
        if (transactionIds.isEmpty()) {
            return out;
        }
        for (Object[] row : attachments.countFor(userId, transactionIds)) {
            out.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    private Attachment load(UUID userId, UUID attachmentId) {
        return attachments.findByIdAndUserId(attachmentId, userId).orElseThrow(() -> new ResourceNotFoundException("Attachment"));
    }

    /** The file type from its first bytes; null when it's not one we accept. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 5 && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            return "application/pdf";
        }
        return null;
    }

    /** Keeps letters, digits and a few separators; the extension always matches the real type. */
    static String safeName(String original, String type) {
        String ext = switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".pdf";
        };
        String base = original == null ? "" : original.replaceAll(".*[/\\\\]", "");
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^\\p{L}\\p{N} ._()-]", "").strip();
        if (base.isEmpty()) {
            base = "receipt";
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        return base + ext;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AttachmentResponse toResponse(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getTransactionId(), a.getFileName(), a.getContentType(), a.getSizeBytes(), a.getCreatedAt());
    }
}
