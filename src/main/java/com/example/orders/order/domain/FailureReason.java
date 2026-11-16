package com.example.orders.order.domain;

/** Why an order ended FAILED; exposed to clients as {@code failureReason}. */
public enum FailureReason {
    /** The plan was deactivated after the order was accepted. */
    PLAN_INACTIVE,
    /** Another order for the same MSISDN (same partner) is already validated, provisioning or active. */
    MSISDN_IN_USE,
    /** The provisioning gateway refused the activation. */
    GATEWAY_REJECTED,
    /** The gateway stayed unavailable through every retry; the line's real state may need reconciling. */
    PROVISIONING_UNAVAILABLE,
    /** A cancellation was requested but the activation could not be undone. */
    COMPENSATION_FAILED
}
