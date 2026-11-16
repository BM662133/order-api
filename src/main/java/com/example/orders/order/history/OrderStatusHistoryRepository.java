package com.example.orders.order.history;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Partner-scoped reads only, like {@code OrderRepository}. */
public interface OrderStatusHistoryRepository extends Repository<OrderStatusHistoryEntry, Long> {

    OrderStatusHistoryEntry save(OrderStatusHistoryEntry entry);

    List<OrderStatusHistoryEntry> findByOrderIdAndPartnerIdOrderByIdAsc(UUID orderId, String partnerId);
}
