package com.example.orders.order.app;

import com.example.orders.order.domain.CancelOutcome;
import com.example.orders.order.domain.Order;

public record CancelResult(Order order, CancelOutcome outcome) {
}
