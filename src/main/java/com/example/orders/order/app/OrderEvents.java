package com.example.orders.order.app;

import java.util.UUID;

import com.example.orders.order.domain.Order;

/** Outbox events the order module records. */
public final class OrderEvents {

    /** Recorded with every new order; starts provisioning. */
    public static final String ORDER_CREATED = "OrderCreated";

    public record OrderCreated(UUID orderId, String partnerId) {
    }

    /**
     * {@code partnerId:msisdn}: all of a partner's orders for one MSISDN share a Kafka partition, so they
     * are validated one at a time even with several consumers. That closes the MSISDN_IN_USE race.
     */
    public static String messageKey(Order order) {
        return order.getPartnerId() + ":" + order.getMsisdn();
    }

    private OrderEvents() {
    }
}
