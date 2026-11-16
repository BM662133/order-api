package com.example.orders.outbox;

public enum OutboxStatus {
    /** Waiting for delivery (first attempt or a retry). */
    PENDING,
    /** The handler completed. */
    PROCESSED,
    /** Retries exhausted, or no handler for the event type; needs a human. */
    DEAD
}
