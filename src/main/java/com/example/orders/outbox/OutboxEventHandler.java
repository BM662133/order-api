package com.example.orders.outbox;

/** Consumes one event type. Delivery is at-least-once, so implementations must be idempotent. */
public interface OutboxEventHandler {

    String eventType();

    /**
     * Throw to have the event retried later with backoff (by the outbox relay in-process, by the Kafka
     * error handler with Kafka). The same event may also arrive again after a successful run.
     */
    void handle(EventEnvelope event);

    /** Every retry failed; the event will not be delivered again. */
    default void onRetriesExhausted(EventEnvelope event) {
    }
}
