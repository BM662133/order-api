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
     * Never tried, because the transport stopped the batch early (e.g. the broker is unreachable). The
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

    static DeliveryResult notAttempted() {
        return new NotAttempted();
    }
}
