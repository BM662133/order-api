package com.example.orders.order.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.example.orders.order.domain.Order;

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
}
