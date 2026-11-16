package com.example.orders.provisioning;

/**
 * The carrier's provisioning system. Both calls must be idempotent per {@link LineRequest#orderId()}:
 * the worker repeats them after crashes and retries, and may deactivate a line that was never activated.
 */
public interface ProvisioningGateway {

    /**
     * @throws GatewayRejectedException permanently refused; the order fails
     * @throws GatewayUnavailableException transient; the outbox relay retries later
     */
    void activate(LineRequest line);

    /** @throws GatewayUnavailableException transient; the outbox relay retries later */
    void deactivate(LineRequest line);
}
