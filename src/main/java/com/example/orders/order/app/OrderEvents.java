package com.example.orders.order.app;

import java.util.UUID;

/** Outbox events the order module records. */
public final class OrderEvents {

    /** Recorded with every new order; starts provisioning. */
    public static final String ORDER_CREATED = "OrderCreated";

    public record OrderCreated(UUID orderId, String partnerId) {
    }

    private OrderEvents() {
    }
}
