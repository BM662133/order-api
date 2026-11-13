package com.example.orders.order.app;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/** Every retry of an order update hit a concurrent change. Rendered as 409; the client may retry. */
public class OrderConcurrentlyModifiedException extends ApiException {

    private final UUID orderId;

    public OrderConcurrentlyModifiedException(UUID orderId, Throwable cause) {
        super(HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION, "Concurrent modification",
                "Order " + orderId + " was modified concurrently. Retry the request.", cause);
        this.orderId = orderId;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("orderId", orderId);
    }
}
