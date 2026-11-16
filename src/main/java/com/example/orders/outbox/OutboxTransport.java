package com.example.orders.outbox;

/**
 * Where {@link OutboxRelay} delivers events: straight to the handlers ({@link InProcessTransport}, the
 * default) or to a broker ({@code orders.outbox.transport=kafka}).
 */
public interface OutboxTransport {

    /**
     * Returns once the event is delivered. Throw to have the relay retry with backoff;
     * {@link UnknownEventTypeException} marks the event DEAD at once.
     */
    void deliver(EventEnvelope event);

    /** The relay gave up on the event after max-attempts failures. */
    void onDead(EventEnvelope event);
}
