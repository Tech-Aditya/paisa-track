package com.paisatrack.modules.recurring;

public enum RuleStatus {
    ACTIVE,
    /** No new occurrences until resumed. */
    PAUSED,
    /** Ended for good. */
    STOPPED
}
