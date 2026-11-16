package com.example.orders.order.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.orders.order.domain.Order;

/**
 * Reserves a partner's idempotency key for one order until {@code expiresAt}. The unique constraint
 * {@code uq_idempotency_keys_partner_key} is what makes concurrent duplicates lose.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private String partnerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    /** Valid for {@code ttl} from the order's creation. */
    public static IdempotencyRecord forOrder(Order order, Duration ttl) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.partnerId = order.getPartnerId();
        record.idempotencyKey = order.getIdempotencyKey();
        record.orderId = order.getId();
        record.createdAt = order.getCreatedAt();
        record.expiresAt = order.getCreatedAt().plus(ttl);
        return record;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public String getPartnerId() {
        return partnerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
