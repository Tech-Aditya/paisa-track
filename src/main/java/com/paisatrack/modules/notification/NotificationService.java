package com.paisatrack.modules.notification;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.notification.dto.NotificationPreferenceResponse;
import com.paisatrack.modules.notification.dto.NotificationResponse;
import com.paisatrack.modules.notification.dto.UpdateNotificationPreferencesRequest;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.api.PageResponse;
import com.paisatrack.platform.exception.ResourceNotFoundException;

/**
 * In-app notifications: history, read state, per-type preferences, and generation. Generation is
 * idempotent: each reminder has a dedupe key, so re-running it (job retries, parallel instances, a
 * manual refresh) never notifies twice for the same item and stage.
 */
@Service
public class NotificationService {

    static final int DEFAULT_DAYS_BEFORE = 3;

    private final NotificationRepository notifications;
    private final ReminderCollector collector;
    private final UserPreferencesService preferences;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, ReminderCollector collector, UserPreferencesService preferences,
                        NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.notifications = notifications;
        this.collector = collector;
        this.preferences = preferences;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, boolean unreadOnly, int page, int size) {
        Page<Notification> result = notifications.findForUser(userId, unreadOnly, PageRequest.of(page, size));
        return PageResponse.of(result, result.getContent().stream().map(NotificationService::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** Repeating it is harmless. */
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification n = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification"));
        n.markRead(clock.instant());
        notifications.flush();
        return toResponse(n);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, clock.instant());
    }

    /** Every type, with defaults (enabled, 3 days before) for those never changed. */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> preferences(UUID userId) {
        Map<NotificationType, NotificationPreferenceResponse> stored = new EnumMap<>(NotificationType.class);
        jdbc.query("select type, enabled, days_before from notification_preferences where user_id = :userId",
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    NotificationType type = NotificationType.valueOf(rs.getString("type"));
                    stored.put(type, new NotificationPreferenceResponse(type.name(), rs.getBoolean("enabled"), rs.getInt("days_before")));
                });
        return Arrays.stream(NotificationType.values())
                .map(t -> stored.getOrDefault(t, new NotificationPreferenceResponse(t.name(), true, DEFAULT_DAYS_BEFORE)))
                .toList();
    }

    @Transactional
    public List<NotificationPreferenceResponse> updatePreferences(UUID userId, UpdateNotificationPreferencesRequest request) {
        for (UpdateNotificationPreferencesRequest.Item item : request.preferences()) {
            jdbc.update("""
                    insert into notification_preferences (user_id, type, enabled, days_before)
                    values (:userId, :type, coalesce(:enabled, true), coalesce(:days, :defaultDays))
                    on conflict (user_id, type) do update set
                        enabled = coalesce(:enabled, notification_preferences.enabled),
                        days_before = coalesce(:days, notification_preferences.days_before),
                        updated_at = now()""",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("type", item.type().name())
                            .addValue("enabled", item.enabled()).addValue("days", item.daysBefore())
                            .addValue("defaultDays", DEFAULT_DAYS_BEFORE));
        }
        return preferences(userId);
    }

    /** Creates any reminders due today in the user's timezone; returns how many are new. */
    @Transactional
    public int generate(UUID userId) {
        LocalDate today = preferences.today(userId);
        Map<NotificationType, Integer> enabled = new EnumMap<>(NotificationType.class);
        preferences(userId).stream().filter(NotificationPreferenceResponse::enabled)
                .forEach(p -> enabled.put(NotificationType.valueOf(p.type()), p.daysBefore()));
        if (enabled.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (ReminderCollector.Reminder r : collector.collect(userId, today, enabled)) {
            created += notifications.insertIfAbsent(userId, r.type().name(), r.stage().name(), truncate(r.title(), 120),
                    truncate(r.message(), 500), r.subjectId(), r.dueDate(), r.type().link(), r.dedupeKey());
        }
        return created;
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType().name(), n.getStage().name(), n.getTitle(), n.getMessage(),
                n.getSubjectId(), n.getDueDate(), n.getLink(), n.getReadAt() != null, n.getCreatedAt(), n.getReadAt());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
