package com.example.orders.order.app;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public Order create(PartnerContext partner, String msisdn, String planCode, SimType simType) {
        return orderRepository.save(Order.create(partner.partnerId(), msisdn, planCode, simType, clock));
    }

    /** @throws OrderNotFoundException if the order does not exist or belongs to another partner */
    @Transactional(readOnly = true)
    public Order get(PartnerContext partner, UUID orderId) {
        return orderRepository.findByIdAndPartnerId(orderId, partner.partnerId())
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
