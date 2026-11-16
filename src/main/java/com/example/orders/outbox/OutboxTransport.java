package com.example.orders.outbox;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Delivers a batch and reports each event's outcome, in the same order as {@code events}. Never
     * throws for a single event's failure. The relay guarantees the batch holds at most one event per
     * message key, so a transport may deliver them concurrently without breaking per-key order.
     *
     * <p>The default delivers one at a time with {@link #deliver} and reports every exception as
     * {@link DeliveryResult.Failed}, so each one counts an attempt. A transport that can do better (Kafka:
     * send everything, then wait for all acks) overrides it, and only a transport that can tell its own
     * outage apart from a bad event should report {@link DeliveryResult.Unavailable}.
     */
    default List<DeliveryResult> deliverAll(List<EventEnvelope> events) {
        List<DeliveryResult> results = new ArrayList<>(events.size());
        for (EventEnvelope event : events) {
            try {
                deliver(event);
                results.add(DeliveryResult.delivered());
            } catch (RuntimeException ex) {
                results.add(DeliveryResult.failed(ex));
            }
        }
        return results;
    }

    /** The relay gave up on the event after max-attempts failures. */
    void onDead(EventEnvelope event);
}
