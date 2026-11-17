package com.example.orders.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step one of a relay pass: claims a batch of due events so that no other relay (another app instance)
 * delivers them at the same time. A short transaction locks the due rows, skipping any another relay has
 * locked, and pushes each one's {@code next_attempt_at} out by the claim lease. After commit the events
 * are simply not due for anyone else until the lease runs out, with no lock held during delivery. If
 * this instance dies mid-pass, they become due again by themselves.
 */
@Component
class OutboxClaims {

    private final OutboxRepository outboxRepository;
    private final OutboxProperties properties;

    OutboxClaims(OutboxRepository outboxRepository, OutboxProperties properties) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    /** @return the claimed events, oldest first, at most one per message key */
    @Transactional
    public List<OutboxEvent> claimDue(Instant now, int limit) {
        List<OutboxEvent> due = outboxRepository.findDueForClaim(OutboxStatus.PENDING, now, Limit.of(limit));
        Instant leaseUntil = now.plus(properties.claimLease());
        due.forEach(event -> event.claim(leaseUntil));
        return due;
    }
}
