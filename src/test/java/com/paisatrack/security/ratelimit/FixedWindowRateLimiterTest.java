package com.paisatrack.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToTheLimitWithinAWindow() {
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();

        FixedWindowRateLimiter.Decision third = limiter.tryAcquire("ip");
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfter()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void keysAreIndependent() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void newWindowResetsTheCount() {
        limiter.tryAcquire("ip");
        limiter.tryAcquire("ip");
        clock.advance(Duration.ofSeconds(45));
        assertThat(limiter.tryAcquire("ip").retryAfter()).isEqualTo(Duration.ofSeconds(15));

        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
    }

    @Test
    void evictionDropsOnlyFinishedWindows() {
        limiter.tryAcquire("old");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("recent");
        clock.advance(Duration.ofSeconds(30));

        limiter.evictExpired();

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
