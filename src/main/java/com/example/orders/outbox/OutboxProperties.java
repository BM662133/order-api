package com.example.orders.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code orders.outbox.*}. The poll interval and the scheduling switch are read directly by
 * {@link OutboxSchedulingConfig}.
 *
 * <p>{@code claimLease}: how long a relay owns the events it claimed (see {@link OutboxClaims}). It must
 * be longer than the slowest relay pass, or another instance re-claims events still being delivered and
 * delivers them twice. Kafka: a pass waits at most {@code orders.outbox.kafka.send-timeout} (15s).
 * In-process: handlers run one after another, so roughly batch-size x the slowest handler.
 */
@ConfigurationProperties("orders.outbox")
public record OutboxProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("60s") Duration maxBackoff,
        @DefaultValue("60s") Duration claimLease) {

    /** Exponential: initial, 2x, 4x, ... capped at {@code maxBackoff}. */
    public Duration backoffAfter(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
        Duration backoff = initialBackoff.multipliedBy(1L << Math.min(attempts - 1, 30));
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
