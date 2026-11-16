package com.example.orders.outbox;

/** What happened to one event in {@link OutboxTransport#deliverAll}. */
public sealed interface DeliveryResult {

    /** Delivered (in-process: the handler finished; Kafka: the broker acknowledged it). */
    record Delivered() implements DeliveryResult {
    }

    /** Tried and failed; the relay counts an attempt and retries with backoff. */
    record Failed(RuntimeException error) implements DeliveryResult {
    }

    /**
     * The transport itself is unavailable (e.g. the broker is down), which says nothing about this event.
     * The relay leaves the event exactly as it was, with no attempt counted, and pauses itself before
     * trying again, so an outage of any length never makes an event DEAD.
     */
    record Unavailable(RuntimeException error) implements DeliveryResult {
    }

    /**
     * Never tried, because the transport stopped the batch early after an {@link Unavailable} result. The
     * relay leaves the event exactly as it was: no attempt is counted.
     */
    record NotAttempted() implements DeliveryResult {
    }

    static DeliveryResult delivered() {
        return new Delivered();
    }

    static DeliveryResult failed(RuntimeException error) {
        return new Failed(error);
    }

    static DeliveryResult unavailable(RuntimeException error) {
        return new Unavailable(error);
    }

    static DeliveryResult notAttempted() {
        return new NotAttempted();
    }
}
