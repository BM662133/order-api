package com.example.orders.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Recorded by {@link Order} on every status change and published by Spring Data when the order is
 * saved. {@code from} is null for the creation entry; {@code reason} is set only for FAILED.
 */
public record OrderStatusChanged(
        UUID orderId,
        String partnerId,
        OrderStatus from,
        OrderStatus to,
        FailureReason reason,
        TriggeredBy triggeredBy,
        Instant occurredAt) {
}
