package com.paisatrack.modules.notification;

import java.time.LocalDate;

/** When a reminder fires. An item gets at most one notification per stage. */
public enum NotificationStage {
    /** Within the "days before" window. */
    UPCOMING,
    DUE,
    OVERDUE,
    /** Not date-based, e.g. a budget threshold reached. */
    ALERT;

    /** Stage of an item due on {@code due}, or null when it is not yet inside the reminder window. */
    static NotificationStage of(LocalDate due, LocalDate today, int daysBefore) {
        if (due.isBefore(today)) {
            return OVERDUE;
        }
        if (due.isEqual(today)) {
            return DUE;
        }
        return due.isAfter(today.plusDays(daysBefore)) ? null : UPCOMING;
    }
}
