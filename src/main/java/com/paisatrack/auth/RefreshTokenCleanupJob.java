package com.paisatrack.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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
        Instant cutoff = clock.instant().minus(retention);
        int deleted = repository.deleteExpiredBefore(cutoff);
        log.info("Refresh token cleanup deleted={}", deleted);
    }
}
