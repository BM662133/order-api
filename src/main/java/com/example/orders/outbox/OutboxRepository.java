package com.example.orders.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends Repository<OutboxEvent, UUID> {

    /** Hibernate's lock timeout meaning SKIP LOCKED ({@code LockOptions.SKIP_LOCKED}). */
    String SKIP_LOCKED = "-2";

    OutboxEvent save(OutboxEvent event);

    /** One transaction for the whole list (SimpleJpaRepository.saveAll is transactional). */
    List<OutboxEvent> saveAll(Iterable<OutboxEvent> events);

    /**
     * Due PENDING events, oldest first, that are at the head of their message key: an event waits while
     * an older PENDING event with the same key exists (even one backing off after a failure). That makes
     * "same key, in order" hold across retries, and means a batch never holds two events for one key.
     * An older DEAD event does not block: one stuck event must not stall its key forever.
     *
     * <p>Locks the rows it returns and skips rows another transaction holds ({@code FOR UPDATE SKIP
     * LOCKED} on Postgres; a dialect without SKIP LOCKED falls back to plain {@code FOR UPDATE}), so it
     * must run in a transaction: {@link OutboxClaims}, which then leases what it got. A claimed event is
     * still PENDING, so it keeps blocking the younger events of its key for every relay.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("""
            select e from OutboxEvent e
            where e.status = :pending and e.nextAttemptAt <= :now
              and not exists (
                  select 1 from OutboxEvent o
                  where o.messageKey = e.messageKey and o.status = :pending
                    and (o.createdAt < e.createdAt or (o.createdAt = e.createdAt and o.id < e.id)))
            order by e.createdAt asc, e.id asc""")
    List<OutboxEvent> findDueForClaim(@Param("pending") OutboxStatus pending, @Param("now") Instant now, Limit limit);
}
