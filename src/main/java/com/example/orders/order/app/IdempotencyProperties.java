package com.example.orders.order.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code orders.idempotency.*}. A key's expiry is fixed when the order is created, so changing the TTL
 * affects new keys only.
 */
@ConfigurationProperties("orders.idempotency")
public record IdempotencyProperties(@DefaultValue("24h") Duration keyTtl) {
}
