package com.example.orders.provisioning;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import com.example.orders.order.app.OrderEvents;
import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.Order;
import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxEventHandler;

/**
 * Drives an order from CREATED to a final status when its {@code OrderCreated} event is delivered:
 *
 * <pre>
 * CREATED      -> eligibility check  -> VALIDATED | FAILED
 * VALIDATED    ->                       PROVISIONING          (committed before calling the gateway)
 * PROVISIONING -> cancel requested?  -> deactivate -> CANCELLED   (compensation)
 *                 otherwise          -> activate   -> ACTIVE | FAILED(GATEWAY_REJECTED)
 * </pre>
 *
 * It always acts on the order's <em>current</em> status, re-read before every step, so a duplicate
 * event or a redelivery after a crash simply resumes where the order is. Gateway calls run outside any
 * transaction. A transient gateway failure propagates and the relay retries the event with backoff.
 *
 * <p>Orders are loaded with the partner-scoped finder using the event's partner, like any other read.
 */
@Component
public class ProvisioningWorker implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);

    /** Generous: the longest path is four steps, plus re-reads after concurrent changes. */
    static final int MAX_STEPS = 10;

    private final ProvisioningSteps steps;
    private final ProvisioningGateway gateway;

    ProvisioningWorker(ProvisioningSteps steps, ProvisioningGateway gateway) {
        this.steps = steps;
        this.gateway = gateway;
    }

    @Override
    public String eventType() {
        return OrderEvents.ORDER_CREATED;
    }

    @Override
    public void handle(EventEnvelope event) {
        String partnerId = event.partnerId();
        UUID orderId = event.aggregateId();
        for (int step = 1; step <= MAX_STEPS; step++) {
            Optional<Order> found = steps.find(partnerId, orderId);
            if (found.isEmpty()) {
                log.warn("Order {} from outbox event {} not found; nothing to provision", orderId, event.eventId());
                return;
            }
            Order order = found.get();
            if (order.getStatus().isTerminal()) {
                log.debug("Order {} is {}; provisioning done", orderId, order.getStatus());
                return;
            }
            try {
                advance(order);
            } catch (ConcurrencyFailureException ex) {
                // e.g. a client cancel committed between our read and our write: re-read and decide again.
                log.debug("Order {} changed concurrently ({}); re-reading", orderId, ex.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("Order " + orderId + " did not reach a final status in " + MAX_STEPS + " steps");
    }

    @Override
    public void onRetriesExhausted(EventEnvelope event) {
        steps.giveUp(event.partnerId(), event.aggregateId());
    }

    private void advance(Order order) {
        String partnerId = order.getPartnerId();
        UUID orderId = order.getId();
        switch (order.getStatus()) {
            case CREATED -> steps.validate(partnerId, orderId);
            case VALIDATED -> steps.startProvisioning(partnerId, orderId);
            case PROVISIONING -> provision(order);
            case ACTIVE, FAILED, CANCELLED -> throw new IllegalStateException("Order " + orderId + " is final");
        }
    }

    private void provision(Order order) {
        String partnerId = order.getPartnerId();
        UUID orderId = order.getId();
        LineRequest line = LineRequest.from(order);
        if (order.isCancelRequested()) {
            gateway.deactivate(line);
            steps.cancellationCompensated(partnerId, orderId);
            return;
        }
        try {
            gateway.activate(line);
        } catch (GatewayRejectedException ex) {
            log.info("Activation rejected for order {}: {}", orderId, ex.getMessage());
            steps.provisioningFailed(partnerId, orderId, FailureReason.GATEWAY_REJECTED);
            return;
        }
        // If a cancel arrived while we were activating, this is a no-op and the next step compensates.
        steps.activated(partnerId, orderId);
    }
}
