package com.paisatrack.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.paisatrack.logging.RequestCorrelation;

/** Append-only audit trail. Writes join the caller's transaction, so the change and its record commit together. */
@Service
public class AuditService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AuditService(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID userId, String entityType, UUID entityId, AuditAction action, Map<String, ?> changes) {
        String json = changes == null || changes.isEmpty() ? null : jsonMapper.writeValueAsString(changes);
        jdbc.update("""
                insert into audit_events (user_id, entity_type, entity_id, action, changes, request_id, occurred_at)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)""",
                userId, entityType, entityId, action.name(), json,
                RequestCorrelation.currentRequestId(), Timestamp.from(clock.instant()));
    }

    /** Oldest first. Scoped to the user, so another user's entity id returns nothing. */
    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(UUID userId, String entityType, UUID entityId) {
        return jdbc.query("""
                select action, changes::text as changes, occurred_at from audit_events
                where user_id = ? and entity_type = ? and entity_id = ?
                order by occurred_at, id""",
                (rs, row) -> new AuditEventResponse(
                        AuditAction.valueOf(rs.getString("action")),
                        rs.getString("changes") == null ? Map.of() : jsonMapper.readValue(rs.getString("changes"), MAP),
                        rs.getTimestamp("occurred_at").toInstant()),
                userId, entityType, entityId);
    }

    /** Fields whose values differ, as {field: {from, to}}. */
    public static Map<String, Object> diff(Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        after.forEach((field, to) -> {
            Object from = before.get(field);
            if (!Objects.equals(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from);
                change.put("to", to);
                changes.put(field, change);
            }
        });
        return changes;
    }
}
