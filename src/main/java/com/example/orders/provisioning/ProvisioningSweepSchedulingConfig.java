package com.example.orders.provisioning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link ProvisioningSweeper}; {@code orders.provisioning.sweep.enabled=false} removes it (tests do). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.provisioning.sweep.enabled", havingValue = "true", matchIfMissing = true)
class ProvisioningSweepSchedulingConfig {

    private final ProvisioningSweeper sweeper;

    ProvisioningSweepSchedulingConfig(ProvisioningSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${orders.provisioning.sweep.interval:1m}")
    void sweep() {
        sweeper.sweep();
    }
}
