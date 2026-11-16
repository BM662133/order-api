package com.example.orders.provisioning;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code orders.provisioning.sweep.*}. {@code enabled} and {@code interval} are read directly by
 * {@link ProvisioningSweepSchedulingConfig}.
 *
 * @param stalledAfter an unfinished order untouched this long, with no pending event, is retried
 * @param batchSize    orders per category per sweep
 */
@ConfigurationProperties("orders.provisioning.sweep")
public record ProvisioningSweepProperties(
        @DefaultValue("15m") Duration stalledAfter,
        @DefaultValue("100") int batchSize) {
}
