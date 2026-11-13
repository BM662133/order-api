package com.example.orders.order.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.orders.order.app.OrderService;
import com.example.orders.order.domain.Order;
import com.example.orders.tenant.PartnerContext;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** The {partnerId} path variable is checked against the caller by PartnerAuthenticationInterceptor. */
    @PostMapping("/partners/{partnerId}/orders")
    public ResponseEntity<OrderResponse> create(PartnerContext partner, @Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.create(partner, request.msisdn(), request.planCode(), request.simType());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/orders/{id}")
                .buildAndExpand(order.getId())
                .toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(PartnerContext partner, @PathVariable UUID orderId) {
        return OrderResponse.from(orderService.get(partner, orderId));
    }
}
