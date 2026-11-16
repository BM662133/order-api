package com.example.orders.provisioning;

/** The gateway permanently refused the request; retrying will not help. */
public class GatewayRejectedException extends RuntimeException {

    public GatewayRejectedException(String message) {
        super(message);
    }
}
