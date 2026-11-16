package com.example.orders.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.TriggeredBy;
import com.example.orders.order.history.OrderStatusHistoryEntry;

/** Wrapped in an object (not a bare array) so paging fields can be added later. */
public record OrderHistoryResponse(UUID orderId, List<Entry> history) {

    /** {@code from} is null for the creation entry; {@code reason} is set only for FAILED. */
    public record Entry(OrderStatus from, OrderStatus to, FailureReason reason, TriggeredBy triggeredBy,
            Instant occurredAt) {
    }

    public static OrderHistoryResponse from(UUID orderId, List<OrderStatusHistoryEntry> entries) {
        return new OrderHistoryResponse(orderId, entries.stream()
                .map(e -> new Entry(e.getFromStatus(), e.getToStatus(), e.getReason(), e.getTriggeredBy(),
                        e.getOccurredAt()))
                .toList());
    }
}
