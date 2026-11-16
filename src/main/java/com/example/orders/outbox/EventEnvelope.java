package com.example.orders.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * An outbox event as handlers and transports see it, independent of where it came from (an outbox row
 * in-process, or a Kafka record).
 *
 * @param eventId    the outbox row id; the same across redeliveries
 * @param messageKey the broker key; events with the same key are delivered in order
 * @param payload    JSON
 */
public record EventEnvelope(UUID eventId, String eventType, UUID aggregateId, String partnerId, String messageKey,
        String payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(payload, "payload");
    }
}
