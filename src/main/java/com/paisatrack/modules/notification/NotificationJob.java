package com.paisatrack.modules.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.paisatrack.modules.user.UserService;

/**
 * Creates due reminders for every active user. Runs hourly so each user's "today" (their own
 * timezone) is picked up soon after midnight. One transaction per user; failures are logged and
 * the rest continue. Dedupe keys make retries and overlapping runs harmless.
 */
@Component
class NotificationJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationJob.class);

    private final UserService users;
    private final NotificationService service;

    NotificationJob(UserService users, NotificationService service) {
        this.users = users;
        this.service = service;
    }

    @Scheduled(cron = "${paisatrack.notifications.cron}")
    public void run() {
        long start = System.nanoTime();
        int checked = 0;
        int created = 0;
        int failed = 0;
        for (UUID userId : users.activeUserIds()) {
            try {
                created += service.generate(userId);
                checked++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Job notifications failed userId={} error={}", userId, e.getClass().getSimpleName());
            }
        }
        log.info("Job notifications done users={} created={} failed={} took={}ms",
                checked, created, failed, (System.nanoTime() - start) / 1_000_000);
    }
}
