package com.example.orders.provisioning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only cross-partner reads of orders, and deliberately limited: they return just (id, partner) so
 * that every follow-up load and write goes through the normal partner-scoped paths.
 */
@Component
class ProvisioningSweepQueries {

    record OrderRef(UUID orderId, String partnerId) {
    }

    private final JdbcTemplate jdbc;

    ProvisioningSweepQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Unfinished, untouched since {@code cutoff}, and nothing queued that would move them on. */
    List<OrderRef> findStalled(Instant cutoff, int limit) {
        return jdbc.query("""
                SELECT o.id, o.partner_id FROM orders o
                WHERE o.status IN ('CREATED', 'VALIDATED', 'PROVISIONING')
                  AND o.updated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.aggregate_id = o.id AND e.status = 'PENDING')
                ORDER BY o.updated_at
                LIMIT ?""",
                (rs, row) -> new OrderRef(rs.getObject("id", UUID.class), rs.getString("partner_id")),
                Timestamp.from(cutoff), limit);
    }

    /** FAILED with an uncertain line state, not yet checked against the gateway. */
    List<OrderRef> findUnreconciled(int limit) {
        return jdbc.query("""
                SELECT id, partner_id FROM orders
                WHERE status = 'FAILED'
                  AND failure_reason IN ('PROVISIONING_UNAVAILABLE', 'COMPENSATION_FAILED')
                  AND reconciled_at IS NULL
                ORDER BY updated_at
                LIMIT ?""",
                (rs, row) -> new OrderRef(rs.getObject("id", UUID.class), rs.getString("partner_id")),
                limit);
    }
}
