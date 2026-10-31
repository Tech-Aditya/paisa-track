package com.paisatrack.modules.user;

import java.time.DayOfWeek;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.platform.persistence.AuditableEntity;
import com.paisatrack.platform.validation.SupportedCurrencies;

/** Per-user settings. The default currency applies to new records only. */
@Entity
@Table(name = "user_preferences")
public class UserPreferences extends AuditableEntity {

    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";
    public static final DayOfWeek DEFAULT_WEEK_START = DayOfWeek.MONDAY;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_start_day", nullable = false, length = 9)
    private DayOfWeek weekStartDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 10)
    private Theme theme;

    protected UserPreferences() {
    }

    UserPreferences(UUID userId, String defaultCurrency, String timezone) {
        this.userId = userId;
        this.defaultCurrency = defaultCurrency != null ? defaultCurrency : SupportedCurrencies.DEFAULT;
        this.timezone = timezone != null ? timezone : DEFAULT_TIMEZONE;
        this.weekStartDay = DEFAULT_WEEK_START;
        this.theme = Theme.SYSTEM;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public String getTimezone() {
        return timezone;
    }

    public DayOfWeek getWeekStartDay() {
        return weekStartDay;
    }

    public Theme getTheme() {
        return theme;
    }

    void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    void setWeekStartDay(DayOfWeek weekStartDay) {
        this.weekStartDay = weekStartDay;
    }

    void setTheme(Theme theme) {
        this.theme = theme;
    }
}
