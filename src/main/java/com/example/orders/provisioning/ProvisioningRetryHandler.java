package com.example.orders.provisioning;

import org.springframework.stereotype.Component;

import com.example.orders.order.app.OrderEvents;
import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxEventHandler;

/**
 * Handles the sweeper's {@code ProvisioningRetryRequested}: the same work as {@code OrderCreated}, since
 * the worker always resumes from the order's current status. A separate type keeps "this order was
 * created" a fact that happens once, for any future consumer of the topic.
 */
@Component
class ProvisioningRetryHandler implements OutboxEventHandler {

    private final ProvisioningWorker worker;

    ProvisioningRetryHandler(ProvisioningWorker worker) {
        this.worker = worker;
    }

    @Override
    public String eventType() {
        return OrderEvents.PROVISIONING_RETRY_REQUESTED;
    }

    @Override
    public void handle(EventEnvelope event) {
        worker.handle(event);
    }

    @Override
    public void onRetriesExhausted(EventEnvelope event) {
        worker.onRetriesExhausted(event);
    }
}
