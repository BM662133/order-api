package com.example.orders.common.error;

import org.springframework.http.HttpStatus;

public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String detail) {
        super(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "Unauthenticated", detail);
    }
}
