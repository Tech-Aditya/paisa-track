package com.paisatrack.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fixed-window counter. Per instance: needs a shared store (Redis) once scaled out. */
public final class FixedWindowRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        Instant now = clock.instant();
        Window current = windows.compute(key, (k, existing) ->
                existing == null || existing.hasEndedAt(now, window)
                        ? new Window(now, 1)
                        : new Window(existing.start(), existing.count() + 1));
        if (current.count() <= maxRequests) {
            return Decision.ALLOWED;
        }
        Duration retryAfter = Duration.between(now, current.start().plus(window));
        return new Decision(false, retryAfter.isNegative() ? Duration.ZERO : retryAfter);
    }

    /** Drops finished windows to keep memory bounded. */
    public void evictExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> entry.getValue().hasEndedAt(now, window));
    }

    int trackedKeys() {
        return windows.size();
    }

    public record Decision(boolean allowed, Duration retryAfter) {
        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    private record Window(Instant start, int count) {
        boolean hasEndedAt(Instant now, Duration length) {
            return !now.isBefore(start.plus(length));
        }
    }
}
