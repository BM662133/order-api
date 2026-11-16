package com.example.orders.provisioning;

import java.util.UUID;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;

/** What the gateway needs to (de)activate a line. {@code orderId} doubles as the idempotency reference. */
public record LineRequest(UUID orderId, String msisdn, String planCode, SimType simType) {

    public static LineRequest from(Order order) {
        return new LineRequest(order.getId(), order.getMsisdn(), order.getPlanCode(), order.getSimType());
    }
}
