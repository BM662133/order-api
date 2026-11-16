package com.example.orders.order.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;

/**
 * Deliberately not a {@code JpaRepository}: there is no unscoped {@code findById}, so every read must
 * name the owning partner.
 */
public interface OrderRepository extends Repository<Order, UUID> {

    Order save(Order order);

    /** Flushes immediately, so a unique-constraint violation surfaces from this call, not at commit. */
    Order saveAndFlush(Order order);

    Optional<Order> findByIdAndPartnerId(UUID id, String partnerId);

    Optional<Order> findByPartnerIdAndIdempotencyKey(String partnerId, String idempotencyKey);

    /** Whether another of the partner's orders for this MSISDN is in one of {@code statuses}. */
    boolean existsByPartnerIdAndMsisdnAndStatusInAndIdNot(String partnerId, String msisdn,
            Collection<OrderStatus> statuses, UUID id);
}
