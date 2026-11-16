package com.example.orders.outbox.kafka;

/** The broker did not acknowledge a record in time; the outbox relay retries it. */
public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
