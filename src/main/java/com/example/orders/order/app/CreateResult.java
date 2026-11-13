package com.example.orders.order.app;

import com.example.orders.order.domain.Order;

/** @param replayed true if the order already existed for this idempotency key */
public record CreateResult(Order order, boolean replayed) {
}
