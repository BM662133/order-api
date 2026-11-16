package com.example.orders.order.api;

import java.time.Instant;
import java.util.UUID;

import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.SimType;

/**
 * {@code failureReason} is null unless status is FAILED; {@code cancelRequestedAt} is set once a client
 * cancelled during PROVISIONING (the order then stays PROVISIONING until the worker has compensated).
 */
public record OrderResponse(
        UUID id,
        String partnerId,
        String msisdn,
        String planCode,
        SimType simType,
        OrderStatus status,
        FailureReason failureReason,
        Instant cancelRequestedAt,
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
                order.getFailureReason(),
                order.getCancelRequestedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
