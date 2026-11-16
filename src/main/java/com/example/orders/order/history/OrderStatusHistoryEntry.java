package com.example.orders.order.history;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.OrderStatusChanged;
import com.example.orders.order.domain.TriggeredBy;

/** One row of an order's status timeline. Append-only: no column is updatable. */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistoryEntry {

    /** Identity, so ordering by id is insertion order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private String partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private FailureReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, updatable = false)
    private TriggeredBy triggeredBy;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected OrderStatusHistoryEntry() {
    }

    static OrderStatusHistoryEntry of(OrderStatusChanged change) {
        OrderStatusHistoryEntry entry = new OrderStatusHistoryEntry();
        entry.orderId = change.orderId();
        entry.partnerId = change.partnerId();
        entry.fromStatus = change.from();
        entry.toStatus = change.to();
        entry.reason = change.reason();
        entry.triggeredBy = change.triggeredBy();
        entry.occurredAt = change.occurredAt();
        return entry;
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public FailureReason getReason() {
        return reason;
    }

    public TriggeredBy getTriggeredBy() {
        return triggeredBy;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
