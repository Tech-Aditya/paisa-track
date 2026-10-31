package com.paisatrack.modules.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class FrequencyTest {

    @Test
    void monthlyFromTheThirtyFirstFollowsMonthEndsWithoutDrifting() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        assertThat(Frequency.MONTHLY.dateOf(start, 1, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Frequency.MONTHLY.dateOf(start, 1, 2)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(Frequency.MONTHLY.dateOf(start, 1, 3)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(Frequency.QUARTERLY.dateOf(start, 1, 1)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(Frequency.YEARLY.dateOf(LocalDate.of(2024, 2, 29), 1, 1)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(Frequency.YEARLY.dateOf(LocalDate.of(2024, 2, 29), 1, 4)).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void intervalsMultiplyThePeriod() {
        LocalDate start = LocalDate.of(2026, 10, 5);
        assertThat(Frequency.WEEKLY.dateOf(start, 2, 3)).isEqualTo(start.plusWeeks(6));
        assertThat(Frequency.DAILY.dateOf(start, 10, 2)).isEqualTo(start.plusDays(20));
        assertThat(Frequency.MONTHLY.dateOf(start, 6, 1)).isEqualTo(LocalDate.of(2027, 4, 5));
    }

    @Test
    void firstIndexOnOrAfterFindsTheExactOccurrence() {
        LocalDate start = LocalDate.of(2020, 1, 31);
        for (Frequency f : Frequency.values()) {
            for (int interval : new int[] {1, 2, 3}) {
                for (LocalDate date = LocalDate.of(2019, 12, 1); date.isBefore(LocalDate.of(2027, 3, 1)); date = date.plusDays(17)) {
                    int index = f.firstIndexOnOrAfter(start, interval, date);
                    assertThat(f.dateOf(start, interval, index)).isAfterOrEqualTo(date);
                    if (index > 0) {
                        assertThat(f.dateOf(start, interval, index - 1)).isBefore(date);
                    }
                }
            }
        }
    }
}
