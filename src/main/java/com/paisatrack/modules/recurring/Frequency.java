package com.paisatrack.modules.recurring;

import java.time.LocalDate;

/**
 * How often a rule repeats. Dates are counted from the start date, never from the previous
 * occurrence, so a rule starting on the 31st falls on the last day of shorter months and
 * returns to the 31st afterwards.
 */
public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY;

    /** Due date of occurrence {@code index} (0 = the start date) when repeating every {@code interval} periods. */
    LocalDate dateOf(LocalDate start, int interval, int index) {
        long steps = (long) interval * index;
        return switch (this) {
            case DAILY -> start.plusDays(steps);
            case WEEKLY -> start.plusWeeks(steps);
            case MONTHLY -> start.plusMonths(steps);
            case QUARTERLY -> start.plusMonths(3 * steps);
            case YEARLY -> start.plusYears(steps);
        };
    }

    /** Index of the first occurrence on or after {@code date}. */
    int firstIndexOnOrAfter(LocalDate start, int interval, LocalDate date) {
        if (!date.isAfter(start)) {
            return 0;
        }
        // Estimate from the day span, then walk to the exact index (month lengths vary).
        long approxDays = switch (this) {
            case DAILY -> 1;
            case WEEKLY -> 7;
            case MONTHLY -> 28;
            case QUARTERLY -> 89;
            case YEARLY -> 365;
        } * (long) interval;
        int index = (int) Math.max(0, (date.toEpochDay() - start.toEpochDay()) / approxDays - 1);
        while (index > 0 && !dateOf(start, interval, index - 1).isBefore(date)) {
            index--;
        }
        while (dateOf(start, interval, index).isBefore(date)) {
            index++;
        }
        return index;
    }
}
