package com.example.orders.order.app;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

public class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_IDEMPOTENCY_KEY, "Invalid idempotency key", detail);
    }
}
