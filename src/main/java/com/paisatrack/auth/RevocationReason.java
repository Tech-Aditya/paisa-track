package com.paisatrack.auth;

enum RevocationReason {
    /** Swapped for a new token via /refresh. */
    ROTATED,
    LOGOUT,
    LOGOUT_ALL,
    /** A rotated token was replayed; the family is treated as stolen. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ACCOUNT_DISABLED
}
