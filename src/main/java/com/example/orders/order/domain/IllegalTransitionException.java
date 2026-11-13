package com.example.orders.order.domain;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/** A requested status change that the {@link OrderStatus} state machine does not allow. Rendered as 409. */
public class IllegalTransitionException extends ApiException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalTransitionException(OrderStatus from, OrderStatus to) {
        super(HttpStatus.CONFLICT, ProblemTypes.ILLEGAL_STATUS_TRANSITION, "Illegal status transition",
                "Cannot transition order from " + from + " to " + to + ".");
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("currentStatus", from, "targetStatus", to);
    }
}
