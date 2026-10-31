package com.paisatrack.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;

/**
 * Makes create requests safe to retry. A repeated Idempotency-Key with the same body returns the
 * original resource; with a different body it is rejected. Concurrent duplicates serialize on the
 * key's primary key, so only one creates.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    IdempotencyService(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Runs {@code create} once per key and returns the created resource id.
     * Without a key it simply runs {@code create}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID execute(UUID userId, String key, String operation, Object request, Supplier<UUID> create) {
        if (key == null) {
            return create.get();
        }
        if (!KEY.matcher(key).matches()) {
            throw new FieldValidationException(HEADER, "must be 8-100 characters: letters, digits, . _ : -");
        }
        String hash = hash(operation, request);
        // Blocks while another transaction holds the same key, then does nothing if that one committed.
        int claimed = jdbc.update("""
                insert into idempotency_keys (user_id, idempotency_key, operation, request_hash)
                values (?, ?, ?, ?) on conflict do nothing""", userId, key, operation, hash);
        if (claimed == 1) {
            UUID id = create.get();
            jdbc.update("update idempotency_keys set resource_id = ? where user_id = ? and idempotency_key = ?",
                    id, userId, key);
            return id;
        }
        List<StoredKey> stored = jdbc.query("""
                select operation, request_hash, resource_id from idempotency_keys
                where user_id = ? and idempotency_key = ?""",
                (rs, row) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)),
                userId, key);
        StoredKey existing = stored.getFirst();
        if (!existing.operation().equals(operation) || !existing.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request");
        }
        log.info("Idempotent replay operation={} resourceId={}", operation, existing.resourceId());
        return existing.resourceId();
    }

    private String hash(String operation, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update(jsonMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record StoredKey(String operation, String requestHash, UUID resourceId) {
    }
}
