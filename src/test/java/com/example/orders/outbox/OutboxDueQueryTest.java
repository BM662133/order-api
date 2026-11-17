package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The head-of-line rule in {@link OutboxRepository#findDueForClaim}, against H2. The database is shared with
 * other test classes, so each test uses its own keys and looks only at its own events.
 */
@SpringBootTest
class OutboxDueQueryTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private OutboxEvent save(String key, Instant createdAt) {
        return repository.save(OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent", key, "{}",
                Clock.fixed(createdAt, ZoneOffset.UTC)));
    }

    private void set(OutboxEvent event, String column, Object value) {
        jdbc.update("UPDATE outbox_events SET " + column + " = ? WHERE id = ?", value, event.getId());
    }

    /** Which of {@code mine} the relay would pick now, in its order. */
    private List<UUID> dueAmong(OutboxEvent... mine) {
        List<UUID> ids = Arrays.stream(mine).map(OutboxEvent::getId).toList();
        // The query locks what it returns, so it needs a transaction (rolled back: nothing is claimed).
        return transaction.execute(status -> {
            status.setRollbackOnly();
            return repository.findDueForClaim(OutboxStatus.PENDING, NOW, Limit.unlimited()).stream()
                    .map(OutboxEvent::getId)
                    .filter(ids::contains)
                    .toList();
        });
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void onlyTheOldestPendingEventOfAKeyIsDue() {
        String key = key();
        OutboxEvent first = save(key, NOW.minusSeconds(30));
        OutboxEvent second = save(key, NOW.minusSeconds(20));
        OutboxEvent third = save(key, NOW.minusSeconds(10));

        assertThat(dueAmong(first, second, third)).containsExactly(first.getId());
    }

    @Test
    void anOlderEventBackingOffStillBlocksItsKey() {
        String key = key();
        OutboxEvent failed = save(key, NOW.minusSeconds(30));
        OutboxEvent next = save(key, NOW.minusSeconds(20));
        set(failed, "next_attempt_at", Timestamp.from(NOW.plusSeconds(60)));

        assertThat(dueAmong(failed, next)).as("neither: the head isn't due and the next must wait").isEmpty();
    }

    @Test
    void processedAndDeadEventsDoNotBlock() {
        String key = key();
        OutboxEvent processed = save(key, NOW.minusSeconds(30));
        OutboxEvent dead = save(key, NOW.minusSeconds(20));
        OutboxEvent next = save(key, NOW.minusSeconds(10));
        set(processed, "status", "PROCESSED");
        set(dead, "status", "DEAD");

        assertThat(dueAmong(processed, dead, next)).containsExactly(next.getId());
    }

    @Test
    void differentKeysDoNotBlockEachOtherAndComeOldestFirst() {
        OutboxEvent b = save(key(), NOW.minusSeconds(10));
        OutboxEvent a = save(key(), NOW.minusSeconds(20));

        assertThat(dueAmong(a, b)).containsExactly(a.getId(), b.getId());
    }

    @Test
    void sameKeyAndSameTimestampStillYieldsExactlyOneHead() {
        String key = key();
        Instant at = NOW.minusSeconds(10);
        OutboxEvent one = save(key, at);
        OutboxEvent two = save(key, at);

        assertThat(dueAmong(one, two)).hasSize(1);
    }

    @Test
    void eventsNotYetDueAreNotReturned() {
        OutboxEvent later = save(key(), NOW.plusSeconds(10));

        assertThat(dueAmong(later)).isEmpty();
    }
}
