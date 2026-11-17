package com.example.orders.outbox;

import java.time.Clock;
import java.time.Duration;
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
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/** A fact recorded in the same transaction as the change it describes, delivered later by {@link OutboxRelay}. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** The owning tenant, so handlers can use partner-scoped lookups. */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private String partnerId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** Broker key; events with the same key are delivered in order. */
    @Column(name = "message_key", nullable = false, updatable = false)
    private String messageKey;

    /** JSON; what a message broker would carry. */
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    /**
     * Fences relays: claiming bumps it, so a relay whose lease ran out (and whose event another relay
     * re-claimed) fails to record its outcome instead of overwriting the newer one.
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** Not persisted: {@code nextAttemptAt} before this relay claimed the event, to release it unchanged. */
    @Transient
    private Instant dueBeforeClaim;

    protected OutboxEvent() {
    }

    private OutboxEvent(UUID aggregateId, String partnerId, String eventType, String messageKey, String payload,
            Instant now) {
        this.id = UUID.randomUUID();
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.partnerId = Objects.requireNonNull(partnerId, "partnerId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    /** A new event, due immediately. */
    public static OutboxEvent pending(UUID aggregateId, String partnerId, String eventType, String messageKey,
            String payload, Clock clock) {
        return new OutboxEvent(aggregateId, partnerId, eventType, messageKey, payload, now(clock));
    }

    public EventEnvelope toEnvelope() {
        return new EventEnvelope(id, eventType, aggregateId, partnerId, messageKey, payload);
    }

    /**
     * Also ends the claim: a processed row keeps its original due time rather than a lease, so republishing
     * it by setting it back to PENDING makes it due at once.
     */
    void markProcessed(Clock clock) {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = now(clock);
        if (dueBeforeClaim != null) {
            this.nextAttemptAt = dueBeforeClaim;
            this.dueBeforeClaim = null;
        }
    }

    /** Counts a failed attempt and makes the event due again after {@code retryAfter}. */
    void recordFailure(String error, Duration retryAfter, Clock clock) {
        this.attempts++;
        this.lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        this.nextAttemptAt = now(clock).plus(retryAfter);
    }

    void markDead() {
        this.status = OutboxStatus.DEAD;
    }

    /** Takes the event out of everyone else's due set until {@code leaseUntil}. */
    void claim(Instant leaseUntil) {
        this.dueBeforeClaim = nextAttemptAt;
        this.nextAttemptAt = leaseUntil.truncatedTo(ChronoUnit.MICROS);
    }

    /** Gives a claimed event back exactly as it was (due when it was due), with no attempt counted. */
    void releaseClaim() {
        if (dueBeforeClaim == null) {
            throw new IllegalStateException("Event " + id + " was not claimed");
        }
        this.nextAttemptAt = dueBeforeClaim;
        this.dueBeforeClaim = null;
    }

    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    Long getVersion() {
        return version;
    }
}
