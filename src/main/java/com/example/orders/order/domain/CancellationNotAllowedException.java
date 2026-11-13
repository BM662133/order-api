package com.example.orders.order.domain;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/**
 * The state machine allows the transition, but a client may not trigger it in this status (cancelling
 * mid-provisioning needs compensation by the provisioning worker). Rendered as 409.
 */
public class CancellationNotAllowedException extends ApiException {

    private final UUID orderId;
    private final OrderStatus currentStatus;

    public CancellationNotAllowedException(UUID orderId, OrderStatus currentStatus) {
        super(HttpStatus.CONFLICT, ProblemTypes.CANCELLATION_NOT_ALLOWED, "Cancellation not allowed",
                "Order cannot be cancelled while " + currentStatus + ".");
        this.orderId = orderId;
        this.currentStatus = currentStatus;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("orderId", orderId, "currentStatus", currentStatus);
    }
}
