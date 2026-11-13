package com.example.orders.order.app;

import java.sql.SQLException;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;

/** Tells a duplicate idempotency key apart from every other integrity violation (FKs, CHECKs, ...). */
final class IdempotencyConflicts {

    static final String CONSTRAINT = "uq_orders_partner_idempotency";

    private IdempotencyConflicts() {
    }

    /**
     * Prefers Hibernate's extracted constraint name and falls back to the SQL message. Both are compared
     * case-insensitively by substring, because databases upper-case and decorate names (H2 reports
     * {@code UQ_ORDERS_PARTNER_IDEMPOTENCY_INDEX_8}).
     */
    static boolean isKeyConflict(DataIntegrityViolationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException violation
                    && mentionsConstraint(violation.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException && mentionsConstraint(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsConstraint(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(CONSTRAINT);
    }
}
