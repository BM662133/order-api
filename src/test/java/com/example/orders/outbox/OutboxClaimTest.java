package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The claim lifecycle against a real database (H2): lease, exclusion, head-of-line while claimed, lease
 * expiry, release, and the version fence. Times are in the year 2000, so only this test's events are
 * due at {@code NOW} and the leases it hands out are long over in real time (other classes share this
 * database).
 */
@SpringBootTest
class OutboxClaimTest {

    private static final Instant CREATED = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant NOW = CREATED.plus(Duration.ofHours(1));

    @Autowired
    private OutboxClaims claims;

    @Autowired
    private OutboxOutcomes outcomes;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private OutboxEvent save(String key, int secondsAfterCreated) {
        Clock createdAt = Clock.fixed(CREATED.plusSeconds(secondsAfterCreated), ZoneOffset.UTC);
        return repository.save(OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent", key, "{}", createdAt));
    }

    private static String key() {
        return "claim-" + UUID.randomUUID();
    }

    /** Claims at {@code at}, keeping only {@code mine} (in claim order). */
    private List<OutboxEvent> claim(Instant at, OutboxEvent... mine) {
        List<UUID> ids = Arrays.stream(mine).map(OutboxEvent::getId).toList();
        return claims.claimDue(at, 1000).stream().filter(e -> ids.contains(e.getId())).toList();
    }

    private Instant dueAt(OutboxEvent event) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM outbox_events WHERE id = ?", Timestamp.class,
                event.getId()).toInstant();
    }

    private String statusOf(OutboxEvent event) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, event.getId());
    }

    private long versionOf(OutboxEvent event) {
        return jdbc.queryForObject("SELECT version FROM outbox_events WHERE id = ?", Long.class, event.getId());
    }

    @Test
    void aClaimLeasesTheEventAndBumpsItsVersion() {
        OutboxEvent event = save(key(), 0);
        long before = versionOf(event);

        List<OutboxEvent> claimed = claim(NOW, event);

        assertThat(claimed).hasSize(1);
        assertThat(dueAt(event)).isEqualTo(NOW.plus(properties.claimLease()));
        assertThat(versionOf(event)).isEqualTo(before + 1);
        assertThat(claimed.get(0).getVersion()).as("the claimed copy carries the new version").isEqualTo(before + 1);
    }

    @Test
    void aClaimedEventIsNotClaimedAgainUntilItsLeaseRunsOut() {
        OutboxEvent event = save(key(), 0);
        claim(NOW, event);

        assertThat(claim(NOW.plusSeconds(1), event)).as("leased").isEmpty();
        assertThat(claim(NOW.plus(properties.claimLease()), event)).as("lease over (e.g. its relay died)").hasSize(1);
    }

    @Test
    void aClaimedHeadStillBlocksTheRestOfItsKey() {
        String key = key();
        OutboxEvent head = save(key, 0);
        OutboxEvent next = save(key, 1);

        assertThat(claim(NOW, head, next)).extracting(OutboxEvent::getId).containsExactly(head.getId());
        assertThat(claim(NOW, head, next)).as("next waits while the head is claimed").isEmpty();
    }

    @Test
    void aReleasedEventIsDueExactlyWhenItWasBefore() {
        OutboxEvent event = save(key(), 0);
        Instant originallyDue = dueAt(event);
        OutboxEvent claimed = claim(NOW, event).get(0);

        claimed.releaseClaim();
        outcomes.record(List.of(claimed));

        assertThat(dueAt(event)).isEqualTo(originallyDue);
        assertThat(claim(NOW, event)).as("claimable again at once").hasSize(1);
    }

    @Test
    void anOutcomeForAnEventSomeoneElseReclaimedIsDroppedAndTheRestAreSaved() {
        OutboxEvent mine = save(key(), 0);
        OutboxEvent reclaimed = save(key(), 1);
        List<OutboxEvent> claimed = claim(NOW, mine, reclaimed);
        // Another relay re-claims one of them after our lease ran out: its write bumps the version.
        jdbc.update("UPDATE outbox_events SET version = version + 1 WHERE id = ?", reclaimed.getId());
        Clock now = Clock.fixed(NOW, ZoneOffset.UTC);
        claimed.forEach(e -> e.markProcessed(now));

        List<OutboxEvent> saved = outcomes.record(claimed);

        assertThat(saved).extracting(OutboxEvent::getId).containsExactly(mine.getId());
        assertThat(statusOf(mine)).isEqualTo("PROCESSED");
        assertThat(statusOf(reclaimed)).as("the newer owner's state is kept").isEqualTo("PENDING");
    }
}
