package com.example.orders.provisioning;

/** The gateway could not be reached or timed out; the request may be retried. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }
}
