package com.paisatrack.platform.logging;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Per-request correlation id, kept in the logging MDC. */
public final class RequestCorrelation {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    /** Set by the security layer; cleared here once the access line is written. */
    public static final String USER_MDC_KEY = "userId";

    // Client ids must be short and safe so they can't inject into logs or headers.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private RequestCorrelation() {
    }

    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }

    /** 16 hex chars: short in logs, unique enough for correlation. */
    static String newRequestId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    static boolean isAcceptable(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches();
    }
}
