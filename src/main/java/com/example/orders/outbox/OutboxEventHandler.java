package com.example.orders.outbox;

/** Consumes one event type. Delivery is at-least-once, so implementations must be idempotent. */
public interface OutboxEventHandler {

    String eventType();

    /**
     * Throw to have the relay retry later with backoff; the same event may also arrive again after a
     * successful run (crash before it was marked processed).
     */
    void handle(OutboxEvent event);

    /** The event failed {@code max-attempts} times and is now DEAD; it will not be delivered again. */
    default void onRetriesExhausted(OutboxEvent event) {
    }
}
