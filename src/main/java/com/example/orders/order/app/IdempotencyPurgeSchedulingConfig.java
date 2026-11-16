package com.example.orders.order.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link IdempotencyKeyPurge}; {@code orders.idempotency.purge-enabled=false} removes it (tests do). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.idempotency.purge-enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyPurgeSchedulingConfig {

    private final IdempotencyKeyPurge purge;

    IdempotencyPurgeSchedulingConfig(IdempotencyKeyPurge purge) {
        this.purge = purge;
    }

    @Scheduled(fixedDelayString = "${orders.idempotency.purge-interval:1h}")
    void purgeExpiredKeys() {
        purge.purgeExpired();
    }
}
