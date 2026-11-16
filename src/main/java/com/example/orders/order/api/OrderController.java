package com.example.orders.order.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.orders.order.app.CancelResult;
import com.example.orders.order.app.CreateResult;
import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.app.OrderService;
import com.example.orders.order.domain.Order;
import com.example.orders.tenant.PartnerContext;

@RestController
public class OrderController {

    /** Set to "true" when a create request was answered with an order that already existed for its key. */
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * The {partnerId} path variable is checked against the caller by PartnerAuthenticationInterceptor.
     * The idempotency header is optional here so that a missing one gets our ProblemDetail 400 (from
     * {@link IdempotencyKey}) rather than Spring's generic missing-header response.
     */
    @PostMapping("/partners/{partnerId}/orders")
    public ResponseEntity<OrderResponse> create(PartnerContext partner,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateResult result = orderService.create(
                partner, idempotencyKey, request.msisdn(), request.planCode(), request.simType());
        Order order = result.order();
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/orders/{id}")
                .buildAndExpand(order.getId())
                .toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED_HEADER, "true");
        }
        return response.body(OrderResponse.from(order));
    }

    /**
     * 200 once the order is cancelled (repeats included). 202 while PROVISIONING: the cancellation is
     * recorded and the provisioning worker completes it after undoing the activation.
     */
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(PartnerContext partner, @PathVariable UUID orderId) {
        CancelResult result = orderService.cancel(partner, orderId);
        HttpStatus status = result.outcome().pending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(OrderResponse.from(result.order()));
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(PartnerContext partner, @PathVariable UUID orderId) {
        return OrderResponse.from(orderService.get(partner, orderId));
    }

    @GetMapping("/orders/{orderId}/history")
    public OrderHistoryResponse history(PartnerContext partner, @PathVariable UUID orderId) {
        return OrderHistoryResponse.from(orderId, orderService.history(partner, orderId));
    }
}
