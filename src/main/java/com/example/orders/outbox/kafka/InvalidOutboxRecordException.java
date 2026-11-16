package com.example.orders.outbox.kafka;

/** A record on the outbox topic that cannot be turned into an event; retrying cannot help. */
public class InvalidOutboxRecordException extends RuntimeException {

    public InvalidOutboxRecordException(String message) {
        super(message);
    }
}
