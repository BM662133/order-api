package com.example.orders.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    /** One transaction for the whole list (SimpleJpaRepository.saveAll is transactional). */
    List<OutboxEvent> saveAll(Iterable<OutboxEvent> events);

    /**
     * Due PENDING events, oldest first, that are at the head of their message key: an event waits while
     * an older PENDING event with the same key exists (even one backing off after a failure). That makes
     * "same key, in order" hold across retries, and means a batch never holds two events for one key.
     * An older DEAD event does not block: one stuck event must not stall its key forever.
     *
     * <p>Single-instance only: with several relays on Postgres this would be a native
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} so that each event is claimed by one relay.
     */
    @Query("""
            select e from OutboxEvent e
            where e.status = :pending and e.nextAttemptAt <= :now
              and not exists (
                  select 1 from OutboxEvent o
                  where o.messageKey = e.messageKey and o.status = :pending
                    and (o.createdAt < e.createdAt or (o.createdAt = e.createdAt and o.id < e.id)))
            order by e.createdAt asc, e.id asc""")
    List<OutboxEvent> findDue(@Param("pending") OutboxStatus pending, @Param("now") Instant now, Limit limit);
}
