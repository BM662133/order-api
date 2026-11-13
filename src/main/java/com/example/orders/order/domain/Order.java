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

    /** Unique per partner (uq_orders_partner_idempotency). */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the normalized create request; tells a genuine retry from a reused key. */
    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

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

    private Order(String partnerId, String idempotencyKey, String requestFingerprint, String msisdn,
            String planCode, SimType simType, Instant now) {
        this.id = UUID.randomUUID();
        this.partnerId = Objects.requireNonNull(partnerId, "partnerId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.msisdn = normalizeMsisdn(Objects.requireNonNull(msisdn, "msisdn"));
        this.planCode = Objects.requireNonNull(planCode, "planCode");
        this.simType = Objects.requireNonNull(simType, "simType");
        this.status = OrderStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @param msisdn E.164, with or without the leading '+' (format is validated at the API boundary) */
    public static Order create(String partnerId, String idempotencyKey, String requestFingerprint, String msisdn,
            String planCode, SimType simType, Clock clock) {
        return new Order(partnerId, idempotencyKey, requestFingerprint, msisdn, planCode, simType, now(clock));
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

    /**
     * Client-requested cancellation. The state machine also allows PROVISIONING -> CANCELLED, but that
     * edge belongs to the provisioning worker after it has undone the activation, so clients may not
     * take it directly.
     *
     * @return {@code true} if the order was cancelled now, {@code false} if it was already CANCELLED
     *         (cancel is idempotent)
     * @throws CancellationNotAllowedException while PROVISIONING
     * @throws IllegalTransitionException from ACTIVE or FAILED
     */
    public boolean cancel(Clock clock) {
        return switch (status) {
            case CANCELLED -> false;
            case PROVISIONING -> throw new CancellationNotAllowedException(id, status);
            case CREATED, VALIDATED, ACTIVE, FAILED -> {
                transitionTo(OrderStatus.CANCELLED, clock);
                yield true;
            }
        };
    }

    /** E.164 with or without '+' to the stored form (digits only). */
    public static String normalizeMsisdn(String msisdn) {
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
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
