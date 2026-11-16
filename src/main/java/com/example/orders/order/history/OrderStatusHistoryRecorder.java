package com.example.orders.order.history;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.OrderStatusChanged;

/**
 * Writes the status changes that Spring Data publishes when an order is saved.
 *
 * <p>A plain {@code @EventListener}, not {@code @TransactionalEventListener(AFTER_COMMIT)}: the row must
 * commit or roll back with the change it describes. Otherwise a crash after commit could leave a status
 * with no history, and a rolled-back change could leave history behind. Spring Data publishes inside the
 * repository's own transaction, so even a bare {@code save} outside a service transaction stays atomic.
 * {@code MANDATORY} guards against the event being published some other way, with no transaction.
 */
@Component
class OrderStatusHistoryRecorder {

    private final OrderStatusHistoryRepository historyRepository;

    OrderStatusHistoryRecorder(OrderStatusHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OrderStatusChanged change) {
        historyRepository.save(OrderStatusHistoryEntry.of(change));
    }
}
