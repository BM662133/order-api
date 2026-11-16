package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;

@SpringBootTest
class IdempotencyKeyPurgeTest {

    @Autowired
    private IdempotencyKeyPurge purge;

    @Autowired
    private OrderWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private String insertOrderWithKey() {
        String key = UUID.randomUUID().toString();
        writer.insert(Order.create("partner-a", key, "0".repeat(64), "+447700900123", "BASIC_5G", SimType.ESIM, clock));
        return key;
    }

    private boolean keyExists(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class,
                key) == 1;
    }

    @Test
    void onlyExpiredKeysArePurgedAndTheirOrdersRemain() {
        String expired = insertOrderWithKey();
        String live = insertOrderWithKey();
        jdbc.update("UPDATE idempotency_keys SET expires_at = ? WHERE idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), expired);

        assertThat(purge.purgeExpired()).isPositive();

        assertThat(keyExists(expired)).isFalse();
        assertThat(keyExists(live)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key = ?", Integer.class, expired))
                .isEqualTo(1);
    }
}
