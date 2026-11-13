package com.example.orders.order.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Order lifecycle and its allowed transitions:
 *
 * <pre>
 * CREATED ──► VALIDATED ──► PROVISIONING ──► ACTIVE
 *    │            │              │
 *    ├────────────┴──────────────┴──► FAILED
 *    └────────────┴──────────────┴──► CANCELLED
 * </pre>
 *
 * ACTIVE, FAILED and CANCELLED are terminal.
 */
public enum OrderStatus {

    CREATED,
    VALIDATED,
    PROVISIONING,
    ACTIVE,
    FAILED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_NEXT = new EnumMap<>(OrderStatus.class);

    static {
        for (OrderStatus status : values()) {
            ALLOWED_NEXT.put(status, Collections.unmodifiableSet(status.declaredNextStates()));
        }
    }

    /**
     * Exhaustive switch: adding a constant without declaring its transitions does not compile. (Passing
     * later constants to an enum constructor would be an illegal forward reference.)
     */
    private Set<OrderStatus> declaredNextStates() {
        return switch (this) {
            case CREATED -> EnumSet.of(VALIDATED, FAILED, CANCELLED);
            case VALIDATED -> EnumSet.of(PROVISIONING, FAILED, CANCELLED);
            case PROVISIONING -> EnumSet.of(ACTIVE, FAILED, CANCELLED);
            case ACTIVE, FAILED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public Set<OrderStatus> allowedNextStates() {
        return ALLOWED_NEXT.get(this);
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedNextStates().contains(target);
    }

    public boolean isTerminal() {
        return allowedNextStates().isEmpty();
    }

    /** @throws IllegalTransitionException if {@code target} is not an allowed next state */
    public void checkTransitionTo(OrderStatus target) {
        Objects.requireNonNull(target, "target");
        if (!canTransitionTo(target)) {
            throw new IllegalTransitionException(this, target);
        }
    }
}
