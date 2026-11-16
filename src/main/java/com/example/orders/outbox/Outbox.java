package com.example.orders.outbox;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The write side of the outbox: how other modules record an event. */
@Component
public class Outbox {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Outbox(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Records an event in the caller's transaction, so it commits or rolls back together with the change
     * it describes. {@code MANDATORY} turns a call outside a transaction into an error instead of a
     * silently separate commit.
     *
     * @param payload serialized to JSON
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID aggregateId, String partnerId, String eventType, Object payload) {
        outboxRepository.save(OutboxEvent.pending(aggregateId, partnerId, eventType, toJson(payload), clock));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize outbox payload " + payload.getClass().getName(), ex);
        }
    }
}
