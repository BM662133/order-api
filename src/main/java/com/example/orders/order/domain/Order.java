package com.example.orders.order.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A partner's order for a SIM/plan. Status changes only through {@link #transitionTo}, which enforces
 * the {@link OrderStatus} state machine; there are no setters.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private String partnerId;

    /** E.164 digits without the leading '+'. */
    @Column(nullable = false, updatable = false)
    private String msisdn;

    @Column(name = "plan_code", nullable = false, updatable = false)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "sim_type", nullable = false, updatable = false)
    private SimType simType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /** Null until first persisted, which also tells Spring Data the entity is new (no SELECT before INSERT). */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    private Order(String partnerId, String msisdn, String planCode, SimType simType, Instant now) {
        this.id = UUID.randomUUID();
        this.partnerId = Objects.requireNonNull(partnerId, "partnerId");
        this.msisdn = normalizeMsisdn(Objects.requireNonNull(msisdn, "msisdn"));
        this.planCode = Objects.requireNonNull(planCode, "planCode");
        this.simType = Objects.requireNonNull(simType, "simType");
        this.status = OrderStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @param msisdn E.164, with or without the leading '+' (format is validated at the API boundary) */
    public static Order create(String partnerId, String msisdn, String planCode, SimType simType, Clock clock) {
        return new Order(partnerId, msisdn, planCode, simType, now(clock));
    }

    /** @throws IllegalTransitionException if the state machine does not allow {@code target} */
    public void transitionTo(OrderStatus target, Clock clock) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new IllegalTransitionException(id, status, target);
        }
        this.status = target;
        this.updatedAt = now(clock);
    }

    private static String normalizeMsisdn(String msisdn) {
        return msisdn.startsWith("+") ? msisdn.substring(1) : msisdn;
    }

    /** The database stores microseconds; truncating here keeps in-memory and reloaded values identical. */
    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public String getPlanCode() {
        return planCode;
    }

    public SimType getSimType() {
        return simType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Order other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
