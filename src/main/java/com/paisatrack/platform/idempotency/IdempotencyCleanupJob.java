package com.paisatrack.platform.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys past their retention. Idempotent, so safe on several instances. */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    IdempotencyCleanupJob(JdbcTemplate jdbc, Clock clock,
                          @Value("${paisatrack.idempotency.retention}") Duration retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${paisatrack.idempotency.cleanup-cron}")
    void purgeExpiredKeys() {
        long start = System.nanoTime();
        int deleted = jdbc.update("delete from idempotency_keys where created_at < ?",
                Timestamp.from(clock.instant().minus(retention)));
        log.info("Job idempotency-key-cleanup done deleted={} took={}ms", deleted, (System.nanoTime() - start) / 1_000_000);
    }
}
