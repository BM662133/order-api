package com.example.orders.order.app;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.Order;
import com.example.orders.order.persistence.OrderRepository;

/**
 * Single-purpose write transactions, in their own bean so that {@link OrderService} calls them through
 * the Spring proxy and can catch a failure (duplicate key, concurrent update) <em>outside</em> the
 * failed, rollback-only transaction, then retry or re-read in a fresh one.
 */
@Component
class OrderWriter {

    private final OrderRepository orderRepository;
    private final Clock clock;

    OrderWriter(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public Order insert(Order order) {
        return orderRepository.saveAndFlush(order);
    }

    /**
     * Loads the partner's order, cancels it and flushes, so a version conflict surfaces from this call.
     *
     * @throws OrderNotFoundException if the order does not exist or belongs to another partner
     */
    @Transactional
    public Order cancel(String partnerId, UUID orderId) {
        Order order = orderRepository.findByIdAndPartnerId(orderId, partnerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.cancel(clock)) {
            orderRepository.saveAndFlush(order);
        }
        return order;
    }
}
