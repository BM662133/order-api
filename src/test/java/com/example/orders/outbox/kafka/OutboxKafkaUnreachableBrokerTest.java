package com.example.orders.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.orders.outbox.OutboxEvent;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.outbox.OutboxRepository;

/**
 * The "kafka" profile pointed at a port where nothing listens: every send blocks for {@code max.block.ms}
 * fetching metadata, then fails. A relay pass must cost one such block, not one per event, no event may
 * lose an attempt to the outage, and the relay must then pause.
 */
@SpringBootTest(properties = {
        "spring.kafka.producer.properties.max.block.ms=500",
        "orders.outbox.initial-backoff=1m",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles("kafka")
@DirtiesContext
class OutboxKafkaUnreachableBrokerTest {

    private static final long MAX_BLOCK_MS = 500;
    private static final int EVENTS = 10;

    @DynamicPropertySource
    static void unreachableBroker(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String bootstrap = "localhost:" + port;
        registry.add("spring.kafka.bootstrap-servers", () -> bootstrap);
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> mine = new ArrayList<>();

    /** Older than anything else in the shared database, so the relay picks these first. */
    private void insertEvents() {
        Clock longAgo = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
        for (int i = 0; i < EVENTS; i++) {
            OutboxEvent event = OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent",
                    "unreachable-" + UUID.randomUUID(), "{}", longAgo);
            repository.save(event);
            mine.add(event.getId());
        }
    }

    private List<Integer> attempts() {
        return mine.stream()
                .map(id -> jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, id))
                .toList();
    }

    /** Don't leave events behind for other test classes' relays. */
    @AfterEach
    void removeMyEvents() {
        mine.forEach(id -> jdbc.update("UPDATE outbox_events SET status = 'PROCESSED' WHERE id = ?", id));
    }

    @Test
    void anUnreachableBrokerCostsOneBlockPerPassSpendsNoAttemptsAndPausesTheRelay() {
        insertEvents();

        long start = System.nanoTime();
        int attempted = relay.relayDue();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(attempted).isZero();
        assertThat(attempts()).containsOnly(0);
        assertThat(elapsedMs).as("about one max.block.ms, not %d of them", EVENTS)
                .isGreaterThanOrEqualTo(MAX_BLOCK_MS - 50)
                .isLessThan(MAX_BLOCK_MS * 4);

        long pausedStart = System.nanoTime();
        assertThat(relay.relayDue()).isZero();
        assertThat((System.nanoTime() - pausedStart) / 1_000_000).as("paused: no send, no block").isLessThan(100);
    }
}
