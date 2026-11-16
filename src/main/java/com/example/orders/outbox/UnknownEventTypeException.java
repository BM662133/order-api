package com.example.orders.outbox;

/** No handler is registered for the event type. Retrying cannot help. */
public class UnknownEventTypeException extends RuntimeException {

    public UnknownEventTypeException(String eventType) {
        super("No handler for event type " + eventType);
    }
}
