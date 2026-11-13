package com.example.orders.order.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.Order;
import com.example.orders.order.persistence.OrderRepository;

/**
 * The insert transaction, in its own bean so that {@link OrderService} calls it through the Spring proxy
 * and can catch a constraint violation <em>outside</em> the failed (rollback-only) transaction.
 */
@Component
class OrderWriter {

    private final OrderRepository orderRepository;

    OrderWriter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order insert(Order order) {
        return orderRepository.saveAndFlush(order);
    }
}
