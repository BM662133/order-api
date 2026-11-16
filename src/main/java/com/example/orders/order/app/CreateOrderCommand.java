package com.example.orders.order.app;

import com.example.orders.order.domain.SimType;
import com.example.orders.plan.KnownPlan;

/**
 * A syntactically valid create request (format checks ran on {@code CreateOrderRequest}). The checks
 * declared here depend on database state, so {@link OrderService#create} runs them only after the
 * idempotency lookup: a retry must replay even if the plan was deactivated since the first attempt.
 */
public record CreateOrderCommand(String msisdn, @KnownPlan String planCode, SimType simType) {
}
