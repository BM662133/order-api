package com.example.orders.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Delivery by calling the handler directly. A handler failure is a delivery failure, so the relay's
 * retries are the handler's retries, and giving up tells the handler.
 */
@Component
@ConditionalOnProperty(name = "orders.outbox.transport", havingValue = "in-process", matchIfMissing = true)
class InProcessTransport implements OutboxTransport {

    private final OutboxEventHandlers handlers;

    InProcessTransport(OutboxEventHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public void deliver(EventEnvelope event) {
        handlers.dispatch(event);
    }

    @Override
    public void onDead(EventEnvelope event) {
        handlers.retriesExhausted(event);
    }
}
