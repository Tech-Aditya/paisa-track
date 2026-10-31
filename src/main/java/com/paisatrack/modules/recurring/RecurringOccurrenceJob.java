package com.paisatrack.modules.recurring;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps expected occurrences generated ahead for every active rule. Each user runs in its own
 * transaction, so one failure doesn't stop the rest; retries and overlapping runs never duplicate.
 */
@Component
class RecurringOccurrenceJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringOccurrenceJob.class);

    private final RecurringRuleRepository rules;
    private final RecurringService service;

    RecurringOccurrenceJob(RecurringRuleRepository rules, RecurringService service) {
        this.rules = rules;
        this.service = service;
    }

    @Scheduled(cron = "${paisatrack.recurring.generation-cron}")
    public void generateAll() {
        long start = System.nanoTime();
        int users = 0;
        int created = 0;
        int failed = 0;
        for (UUID userId : rules.findUsersWithActiveRules()) {
            try {
                created += service.generate(userId);
                users++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Job recurring-generation failed userId={} error={}", userId, e.toString());
            }
        }
        log.info("Job recurring-generation done users={} created={} failed={} took={}ms",
                users, created, failed, (System.nanoTime() - start) / 1_000_000);
    }
}
