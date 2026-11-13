package com.example.orders.order.app;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/** Also thrown for another partner's order, so a 404 never reveals that the order exists. */
public class OrderNotFoundException extends ApiException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super(HttpStatus.NOT_FOUND, ProblemTypes.ORDER_NOT_FOUND, "Order not found",
                "Order " + orderId + " not found.");
        this.orderId = orderId;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("orderId", orderId);
    }
}
