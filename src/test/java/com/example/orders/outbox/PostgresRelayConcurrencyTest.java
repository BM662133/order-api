package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orders.support.Concurrently;

/**
 * Two relays (as two app instances would run) draining one outbox on a real PostgreSQL at the same time.
 * Each event must be delivered exactly once, each key's events in creation order, and both relays must
 * have done part of the work, or the test proved nothing about running them together.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@DirtiesContext
class PostgresRelayConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5");

    private static final int KEYS = 20;
    private static final int PER_KEY = 10;
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

    @Autowired
    private OutboxClaims claims;

    @Autowired
    private OutboxOutcomes outcomes;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private record Delivery(String relay, UUID eventId, String key, long sequence) {
    }

    /** Records every delivery, taking a moment per event so that the two relays' passes overlap. */
    private static final class RecordingTransport implements OutboxTransport {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        private final String name;
        private final Queue<Delivery> deliveries;

        RecordingTransport(String name, Queue<Delivery> deliveries) {
            this.name = name;
            this.deliveries = deliveries;
        }

        @Override
        public void deliver(EventEnvelope event) {
            deliveries.add(new Delivery(name, event.eventId(), event.messageKey(), SEQUENCE.incrementAndGet()));
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onDead(EventEnvelope event) {
        }
    }

    /** KEYS x PER_KEY events, created interleaved across keys; returns each key's ids in creation order. */
    private Map<String, List<UUID>> insertEvents() {
        Map<String, List<UUID>> byKey = new java.util.LinkedHashMap<>();
        String run = UUID.randomUUID().toString();
        int n = 0;
        for (int i = 0; i < PER_KEY; i++) {
            for (int k = 0; k < KEYS; k++) {
                String key = run + ":" + k;
                Clock createdAt = Clock.fixed(LONG_AGO.plusMillis(n++), ZoneOffset.UTC);
                OutboxEvent event = repository.save(
                        OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent", key, "{}", createdAt));
                byKey.computeIfAbsent(key, x -> new ArrayList<>()).add(event.getId());
            }
        }
        return byKey;
    }

    private int pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'", Integer.class);
    }

    /** What the scheduler does: poll until nothing is left, logging (here: counting) any failed pass. */
    private int drain(OutboxRelay relay, AtomicInteger failedPasses) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        int passes = 0;
        while (pending() > 0 && System.nanoTime() < deadline) {
            try {
                if (relay.relayDue() == 0) {
                    Thread.sleep(5); // the other relay holds every claimable event right now
                }
            } catch (RuntimeException ex) {
                failedPasses.incrementAndGet();
            }
            passes++;
        }
        return passes;
    }

    @Test
    void twoRelaysDeliverEveryEventExactlyOnceAndInKeyOrder() throws Exception {
        Map<String, List<UUID>> expectedOrder = insertEvents();
        Queue<Delivery> deliveries = new ConcurrentLinkedQueue<>();
        OutboxRelay[] relays = {
                new OutboxRelay(claims, outcomes, new RecordingTransport("A", deliveries), properties, clock),
                new OutboxRelay(claims, outcomes, new RecordingTransport("B", deliveries), properties, clock)};
        AtomicInteger failedPasses = new AtomicInteger();

        Concurrently.run(2, i -> () -> drain(relays[i], failedPasses));

        Map<UUID, Long> timesDelivered = deliveries.stream()
                .collect(Collectors.groupingBy(Delivery::eventId, Collectors.counting()));
        assertThat(timesDelivered).hasSize(KEYS * PER_KEY);
        assertThat(timesDelivered.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey))
                .as("delivered more than once").isEmpty();
        assertThat(failedPasses).as("relay passes that threw").hasValue(0);

        Map<String, List<UUID>> actualOrder = deliveries.stream()
                .sorted(Comparator.comparingLong(Delivery::sequence))
                .collect(Collectors.groupingBy(Delivery::key, Collectors.mapping(Delivery::eventId, Collectors.toList())));
        assertThat(actualOrder).as("each key's events in creation order").isEqualTo(expectedOrder);

        Map<String, Long> perRelay = deliveries.stream()
                .collect(Collectors.groupingBy(Delivery::relay, Collectors.counting()));
        assertThat(perRelay).as("both relays did part of the work").containsOnlyKeys("A", "B");
        assertThat(pending()).isZero();
    }
}
