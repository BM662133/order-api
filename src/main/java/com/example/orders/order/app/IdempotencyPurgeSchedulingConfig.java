package com.example.orders.order.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.orders.common.lock.JobLocks;

/**
 * Runs {@link IdempotencyKeyPurge}, on one instance at a time ({@link JobLocks}).
 * {@code orders.idempotency.purge-enabled=false} removes it (tests do).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.idempotency.purge-enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyPurgeSchedulingConfig {

    /** Its row in {@code job_locks} comes from migration V10. */
    static final String JOB = "idempotency-key-purge";

    private final IdempotencyKeyPurge purge;
    private final JobLocks locks;

    IdempotencyPurgeSchedulingConfig(IdempotencyKeyPurge purge, JobLocks locks) {
        this.purge = purge;
        this.locks = locks;
    }

    @Scheduled(fixedDelayString = "${orders.idempotency.purge-interval:1h}")
    void purgeExpiredKeys() {
        locks.runExclusively(JOB, purge::purgeExpired);
    }
}
