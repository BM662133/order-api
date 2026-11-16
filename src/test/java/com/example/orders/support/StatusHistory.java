package com.example.orders.support;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Reads an order's status history straight from the table, as compact strings for assertions. */
public final class StatusHistory {

    private StatusHistory() {
    }

    /** e.g. {@code ["null->CREATED by CLIENT", "PROVISIONING->FAILED GATEWAY_REJECTED by SYSTEM"]} */
    public static List<String> timeline(JdbcTemplate jdbc, String orderId) {
        return jdbc.query("""
                        SELECT from_status, to_status, reason, triggered_by FROM order_status_history
                        WHERE order_id = ? ORDER BY id""",
                (rs, row) -> rs.getString("from_status") + "->" + rs.getString("to_status")
                        + (rs.getString("reason") != null ? " " + rs.getString("reason") : "")
                        + " by " + rs.getString("triggered_by"),
                UUID.fromString(orderId));
    }

    public static int count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history", Integer.class);
    }
}
