package com.paisatrack.modules.auth;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Nightly delete of long-expired refresh tokens. Idempotent, so safe on several instances. */
@Component
class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final Duration retention;

    RefreshTokenCleanupJob(RefreshTokenRepository repository, Clock clock,
                           @Value("${paisatrack.security.refresh-token-cleanup.retention}") Duration retention) {
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${paisatrack.security.refresh-token-cleanup.cron}")
    @Transactional
    public void purgeExpiredTokens() {
        long start = System.nanoTime();
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(retention));
        log.info("Job refresh-token-cleanup done deleted={} took={}ms", deleted, (System.nanoTime() - start) / 1_000_000);
    }
}
