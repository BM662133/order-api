package com.example.orders.provisioning;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.orders.order.app.OrderEvents;
import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.SimType;
import com.example.orders.outbox.EventEnvelope;

/** The worker's decisions per status, including the concurrent-change branch threads can't hit reliably. */
class ProvisioningWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ProvisioningSteps steps = mock(ProvisioningSteps.class);
    private final ProvisioningGateway gateway = mock(ProvisioningGateway.class);
    private final ProvisioningWorker worker = new ProvisioningWorker(steps, gateway);

    private final Order created = Order.create("partner-a", "k", "0".repeat(64), "+447700900123", "BASIC_5G",
            SimType.ESIM, CLOCK);
    private final UUID id = created.getId();
    private final EventEnvelope event = new EventEnvelope(UUID.randomUUID(), OrderEvents.ORDER_CREATED, id,
            "partner-a", "partner-a:447700900123", "{}");

    /** The same order (same id) moved along legal transitions to {@code target}. */
    private Order in(OrderStatus target) {
        Order order = Order.create("partner-a", "k", "0".repeat(64), "+447700900123", "BASIC_5G", SimType.ESIM, CLOCK);
        ReflectionTestUtils.setField(order, "id", id);
        switch (target) {
            case CREATED -> { }
            case VALIDATED -> order.transitionTo(OrderStatus.VALIDATED, CLOCK);
            case PROVISIONING -> {
                order.transitionTo(OrderStatus.VALIDATED, CLOCK);
                order.transitionTo(OrderStatus.PROVISIONING, CLOCK);
            }
            case ACTIVE -> {
                order.transitionTo(OrderStatus.VALIDATED, CLOCK);
                order.transitionTo(OrderStatus.PROVISIONING, CLOCK);
                order.transitionTo(OrderStatus.ACTIVE, CLOCK);
            }
            case FAILED, CANCELLED -> order.transitionTo(target, CLOCK);
        }
        return order;
    }

    private Order provisioningWithCancelRequested() {
        Order order = in(OrderStatus.PROVISIONING);
        order.cancel(CLOCK);
        return order;
    }

    @Test
    void walksEveryStepToActive() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(created), Optional.of(in(OrderStatus.VALIDATED)),
                Optional.of(in(OrderStatus.PROVISIONING)), Optional.of(in(OrderStatus.ACTIVE)));

        worker.handle(event);

        InOrder order = inOrder(steps, gateway);
        order.verify(steps).validate("partner-a", id);
        order.verify(steps).startProvisioning("partner-a", id);
        order.verify(gateway).activate(LineRequest.from(created));
        order.verify(steps).activated("partner-a", id);
        verify(gateway, never()).deactivate(any());
    }

    @Test
    void aConcurrentChangeIsReReadAndDecidedAgain() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(created), Optional.of(in(OrderStatus.CANCELLED)));
        doThrow(new ObjectOptimisticLockingFailureException(Order.class, id)).when(steps).validate("partner-a", id);

        assertThatCode(() -> worker.handle(event)).doesNotThrowAnyException();

        verify(steps, times(1)).validate("partner-a", id);
        verify(steps, never()).startProvisioning(any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void aRejectedActivationFailsTheOrder() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(in(OrderStatus.PROVISIONING)),
                Optional.of(in(OrderStatus.FAILED)));
        doThrow(new GatewayRejectedException("no")).when(gateway).activate(any());

        worker.handle(event);

        verify(steps).provisioningFailed("partner-a", id, FailureReason.GATEWAY_REJECTED);
        verify(steps, never()).activated(any(), any());
    }

    @Test
    void anUnavailableGatewayPropagatesSoTheRelayRetries() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(in(OrderStatus.PROVISIONING)));
        doThrow(new GatewayUnavailableException("down")).when(gateway).activate(any());

        assertThatThrownBy(() -> worker.handle(event)).isInstanceOf(GatewayUnavailableException.class);

        verify(steps, never()).activated(any(), any());
        verify(steps, never()).provisioningFailed(any(), any(), any());
    }

    @Test
    void aRequestedCancellationIsCompensatedInsteadOfActivating() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(provisioningWithCancelRequested()),
                Optional.of(in(OrderStatus.CANCELLED)));

        worker.handle(event);

        InOrder order = inOrder(steps, gateway);
        order.verify(gateway).deactivate(LineRequest.from(created));
        order.verify(steps).cancellationCompensated("partner-a", id);
        verify(gateway, never()).activate(any());
    }

    @Test
    void aMissingOrderIsIgnored() {
        when(steps.find("partner-a", id)).thenReturn(Optional.empty());

        assertThatCode(() -> worker.handle(event)).doesNotThrowAnyException();

        verifyNoInteractions(gateway);
    }

    @Test
    void anOrderThatNeverSettlesFailsTheDelivery() {
        when(steps.find("partner-a", id)).thenReturn(Optional.of(created));

        assertThatThrownBy(() -> worker.handle(event)).isInstanceOf(IllegalStateException.class);

        verify(steps, times(ProvisioningWorker.MAX_STEPS)).validate("partner-a", id);
    }

    @Test
    void exhaustedRetriesGiveUpOnTheOrder() {
        worker.onRetriesExhausted(event);

        verify(steps).giveUp("partner-a", id);
    }
}
