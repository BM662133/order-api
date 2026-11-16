package com.example.orders.order.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * A partner's order for a SIM/plan. Status changes only through {@link #transitionTo} (directly or via
 * {@link #cancel}, {@link #fail}, {@link #completeCancellation}), which enforces the {@link OrderStatus}
 * state machine; there are no setters.
 *
 * <p>Every status change is also recorded as an {@link OrderStatusChanged}. Spring Data publishes these
 * when the order goes through a repository {@code save*} method ({@link DomainEvents}), and the status
 * history listener writes them in the same transaction. Any write path therefore gets history for free.
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

    /** Set only when status is FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    /** Set when a client cancels during PROVISIONING; the provisioning worker then compensates. */
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    /** Null until first persisted, which also tells Spring Data the entity is new (no SELECT before INSERT). */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Status changes not yet published; see {@link #statusChanges()}. */
    @Transient
    private final List<OrderStatusChanged> statusChanges = new ArrayList<>();

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
        recordChange(null, null, TriggeredBy.CLIENT);
    }

    /** @param msisdn E.164, with or without the leading '+' (format is validated at the API boundary) */
    public static Order create(String partnerId, String idempotencyKey, String requestFingerprint, String msisdn,
            String planCode, SimType simType, Clock clock) {
        return new Order(partnerId, idempotencyKey, requestFingerprint, msisdn, planCode, simType, now(clock));
    }

    /**
     * A system-driven status change (the provisioning worker).
     *
     * @throws IllegalTransitionException if the state machine does not allow {@code target}
     */
    public void transitionTo(OrderStatus target, Clock clock) {
        changeStatus(target, null, TriggeredBy.SYSTEM, clock);
    }

    /** @throws IllegalTransitionException if the order is already in a final status */
    public void fail(FailureReason reason, Clock clock) {
        Objects.requireNonNull(reason, "reason");
        changeStatus(OrderStatus.FAILED, reason, TriggeredBy.SYSTEM, clock);
        this.failureReason = reason;
    }

    /**
     * Client-requested cancellation. During PROVISIONING the activation may already be under way, so the
     * request is only recorded; the provisioning worker undoes the activation and then calls
     * {@link #completeCancellation}.
     *
     * @throws IllegalTransitionException from ACTIVE or FAILED
     */
    public CancelOutcome cancel(Clock clock) {
        return switch (status) {
            case CANCELLED -> CancelOutcome.ALREADY_CANCELLED;
            case PROVISIONING -> {
                if (cancelRequestedAt != null) {
                    yield CancelOutcome.ALREADY_REQUESTED;
                }
                this.cancelRequestedAt = now(clock);
                this.updatedAt = cancelRequestedAt;
                yield CancelOutcome.CANCEL_REQUESTED;
            }
            case CREATED, VALIDATED, ACTIVE, FAILED -> {
                changeStatus(OrderStatus.CANCELLED, null, TriggeredBy.CLIENT, clock);
                yield CancelOutcome.CANCELLED;
            }
        };
    }

    /**
     * Provisioning worker only: PROVISIONING -> CANCELLED once the activation has been undone.
     *
     * @throws IllegalStateException if no cancellation was requested
     */
    public void completeCancellation(Clock clock) {
        if (cancelRequestedAt == null) {
            throw new IllegalStateException("No cancellation was requested for order " + id);
        }
        transitionTo(OrderStatus.CANCELLED, clock);
    }

    public boolean isCancelRequested() {
        return cancelRequestedAt != null;
    }

    /** Status changes since the last save. Spring Data publishes them on {@code save*}, then clears them. */
    @DomainEvents
    public List<OrderStatusChanged> statusChanges() {
        return List.copyOf(statusChanges);
    }

    @AfterDomainEventPublication
    void clearStatusChanges() {
        statusChanges.clear();
    }

    /** The single place a status changes. */
    private void changeStatus(OrderStatus target, FailureReason reason, TriggeredBy triggeredBy, Clock clock) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new IllegalTransitionException(id, status, target);
        }
        OrderStatus from = status;
        this.status = target;
        this.updatedAt = now(clock);
        recordChange(from, reason, triggeredBy);
    }

    private void recordChange(OrderStatus from, FailureReason reason, TriggeredBy triggeredBy) {
        statusChanges.add(new OrderStatusChanged(id, partnerId, from, status, reason, triggeredBy, updatedAt));
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

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
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
