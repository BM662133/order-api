package com.example.orders.order.api;

import java.time.Instant;
import java.util.UUID;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.SimType;

public record OrderResponse(
        UUID id,
        String partnerId,
        String msisdn,
        String planCode,
        SimType simType,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getPartnerId(),
                "+" + order.getMsisdn(),
                order.getPlanCode(),
                order.getSimType(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
