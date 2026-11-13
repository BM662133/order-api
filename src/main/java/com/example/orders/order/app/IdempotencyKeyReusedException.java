package com.example.orders.order.app;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

/** The key was already used by this partner for a different request. 422, as in the IETF Idempotency-Key draft. */
public class IdempotencyKeyReusedException extends ApiException {

    private final UUID orderId;

    public IdempotencyKeyReusedException(UUID orderId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused",
                IdempotencyKey.HEADER + " was already used for a different request.");
        this.orderId = orderId;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("orderId", orderId);
    }
}
