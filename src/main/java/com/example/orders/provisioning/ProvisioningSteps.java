package com.example.orders.provisioning;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.persistence.OrderRepository;

/**
 * One short transaction per provisioning step, in its own bean so {@link ProvisioningWorker} calls them
 * through the Spring proxy. Each step re-reads the order and does nothing unless it is still in the
 * expected status, so repeating a step (duplicate event, retry after a crash) is harmless. A concurrent
 * change surfaces as a {@code ConcurrencyFailureException} from the flush.
 */
@Component
class ProvisioningSteps {

    private final OrderRepository orderRepository;
    private final EligibilityChecker eligibilityChecker;
    private final Clock clock;

    ProvisioningSteps(OrderRepository orderRepository, EligibilityChecker eligibilityChecker, Clock clock) {
        this.orderRepository = orderRepository;
        this.eligibilityChecker = eligibilityChecker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Order> find(String partnerId, UUID orderId) {
        return orderRepository.findByIdAndPartnerId(orderId, partnerId);
    }

    /** CREATED -> VALIDATED, or FAILED if the order is not eligible. */
    @Transactional
    public void validate(String partnerId, UUID orderId) {
        update(partnerId, orderId, inStatus(OrderStatus.CREATED), order -> eligibilityChecker.check(order)
                .ifPresentOrElse(reason -> order.fail(reason, clock),
                        () -> order.transitionTo(OrderStatus.VALIDATED, clock)));
    }

    /** VALIDATED -> PROVISIONING, committed before the gateway is called. */
    @Transactional
    public void startProvisioning(String partnerId, UUID orderId) {
        update(partnerId, orderId, inStatus(OrderStatus.VALIDATED),
                order -> order.transitionTo(OrderStatus.PROVISIONING, clock));
    }

    /**
     * PROVISIONING -> ACTIVE after a successful activation, unless a cancellation arrived meanwhile: then
     * the order stays PROVISIONING and the worker compensates instead.
     */
    @Transactional
    public void activated(String partnerId, UUID orderId) {
        update(partnerId, orderId, inStatus(OrderStatus.PROVISIONING).and(order -> !order.isCancelRequested()),
                order -> order.transitionTo(OrderStatus.ACTIVE, clock));
    }

    /** PROVISIONING -> CANCELLED after the activation has been undone. */
    @Transactional
    public void cancellationCompensated(String partnerId, UUID orderId) {
        update(partnerId, orderId, inStatus(OrderStatus.PROVISIONING), order -> order.completeCancellation(clock));
    }

    /** PROVISIONING -> FAILED. */
    @Transactional
    public void provisioningFailed(String partnerId, UUID orderId, FailureReason reason) {
        update(partnerId, orderId, inStatus(OrderStatus.PROVISIONING), order -> order.fail(reason, clock));
    }

    /** Any unfinished status -> FAILED, once the outbox has given up on the order's event. */
    @Transactional
    public void giveUp(String partnerId, UUID orderId) {
        update(partnerId, orderId, order -> !order.getStatus().isTerminal(), order -> order.fail(
                order.isCancelRequested() ? FailureReason.COMPENSATION_FAILED : FailureReason.PROVISIONING_UNAVAILABLE,
                clock));
    }

    private void update(String partnerId, UUID orderId, Predicate<Order> applies, Consumer<Order> change) {
        orderRepository.findByIdAndPartnerId(orderId, partnerId)
                .filter(applies)
                .ifPresent(order -> {
                    change.accept(order);
                    orderRepository.saveAndFlush(order);
                });
    }

    private static Predicate<Order> inStatus(OrderStatus status) {
        return order -> order.getStatus() == status;
    }
}
