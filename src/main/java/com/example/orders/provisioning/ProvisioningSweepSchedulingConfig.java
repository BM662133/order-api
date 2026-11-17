package com.example.orders.provisioning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.orders.common.lock.JobLocks;

/**
 * Runs {@link ProvisioningSweeper}, on one instance at a time ({@link JobLocks}), so that two instances
 * never re-queue or reconcile the same order at once. {@code orders.provisioning.sweep.enabled=false}
 * removes it (tests do).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.provisioning.sweep.enabled", havingValue = "true", matchIfMissing = true)
class ProvisioningSweepSchedulingConfig {

    /** Its row in {@code job_locks} comes from migration V10. */
    static final String JOB = "provisioning-sweep";

    private final ProvisioningSweeper sweeper;
    private final JobLocks locks;

    ProvisioningSweepSchedulingConfig(ProvisioningSweeper sweeper, JobLocks locks) {
        this.sweeper = sweeper;
        this.locks = locks;
    }

    @Scheduled(fixedDelayString = "${orders.provisioning.sweep.interval:1m}")
    void sweep() {
        locks.runExclusively(JOB, sweeper::sweep);
    }
}
