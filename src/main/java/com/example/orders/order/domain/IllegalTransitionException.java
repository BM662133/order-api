package com.example.orders.order.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/** A requested status change that the {@link OrderStatus} state machine does not allow. Rendered as 409. */
public class IllegalTransitionException extends ApiException {

    private final UUID orderId;
    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalTransitionException(OrderStatus from, OrderStatus to) {
        this(null, from, to);
    }

    /** @param orderId the order concerned, or {@code null} when checking a transition in isolation */
    public IllegalTransitionException(UUID orderId, OrderStatus from, OrderStatus to) {
        super(HttpStatus.CONFLICT, ProblemTypes.ILLEGAL_STATUS_TRANSITION, "Illegal status transition",
                "Cannot transition order from " + from + " to " + to + ".");
        this.orderId = orderId;
        this.from = from;
        this.to = to;
    }

    public UUID orderId() {
        return orderId;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }

    @Override
    public Map<String, Object> properties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (orderId != null) {
            properties.put("orderId", orderId);
        }
        properties.put("currentStatus", from);
        properties.put("targetStatus", to);
        return properties;
    }
}
