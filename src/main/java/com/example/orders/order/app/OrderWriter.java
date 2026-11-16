package com.example.orders.order.app;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.CancelOutcome;
import com.example.orders.order.domain.Order;
import com.example.orders.order.persistence.IdempotencyRecord;
import com.example.orders.order.persistence.IdempotencyRecordRepository;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.outbox.Outbox;

/**
 * Single-purpose write transactions, in their own bean so that {@link OrderService} calls them through
 * the Spring proxy and can catch a failure (duplicate key, concurrent update) <em>outside</em> the
 * failed, rollback-only transaction, then retry or re-read in a fresh one.
 */
@Component
class OrderWriter {

    private final OrderRepository orderRepository;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final Outbox outbox;
    private final IdempotencyProperties idempotencyProperties;
    private final Clock clock;

    OrderWriter(OrderRepository orderRepository, IdempotencyRecordRepository idempotencyRecords, Outbox outbox,
            IdempotencyProperties idempotencyProperties, Clock clock) {
        this.orderRepository = orderRepository;
        this.idempotencyRecords = idempotencyRecords;
        this.outbox = outbox;
        this.idempotencyProperties = idempotencyProperties;
        this.clock = clock;
    }

    /**
     * Inserts the order, reserves its idempotency key and records its {@code OrderCreated} event,
     * atomically. An expired reservation of the same key is deleted first, so the key can be reused.
     * The key row is flushed before the event, so a duplicate key (a concurrent request with the same
     * key won) fails here and rolls everything back.
     */
    @Transactional
    public Order insert(Order order) {
        idempotencyRecords.deleteExpired(order.getPartnerId(), order.getIdempotencyKey(), clock.instant());
        Order saved = orderRepository.saveAndFlush(order);
        idempotencyRecords.saveAndFlush(IdempotencyRecord.forOrder(saved, idempotencyProperties.keyTtl()));
        outbox.append(saved.getId(), saved.getPartnerId(), OrderEvents.ORDER_CREATED, OrderEvents.messageKey(saved),
                new OrderEvents.OrderCreated(saved.getId(), saved.getPartnerId()));
        return saved;
    }

    /**
     * Loads the partner's order, cancels it and flushes, so a version conflict surfaces from this call.
     *
     * @throws OrderNotFoundException if the order does not exist or belongs to another partner
     */
    @Transactional
    public CancelResult cancel(String partnerId, UUID orderId) {
        Order order = orderRepository.findByIdAndPartnerId(orderId, partnerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        CancelOutcome outcome = order.cancel(clock);
        if (outcome.changed()) {
            orderRepository.saveAndFlush(order);
        }
        return new CancelResult(order, outcome);
    }
}
