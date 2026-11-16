package com.example.orders.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

public interface OutboxRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    /**
     * Oldest first. Single-instance only: with several relays on Postgres this would be a native
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} so that each event is claimed by one relay.
     */
    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus status, Instant now,
            Limit limit);
}
