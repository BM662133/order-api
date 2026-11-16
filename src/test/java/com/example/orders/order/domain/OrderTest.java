package com.example.orders.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123456Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String FINGERPRINT = "f".repeat(64);

    private static Order newOrder() {
        return Order.create("partner-a", "key-1", FINGERPRINT, "+447700900123", "BASIC_5G", SimType.ESIM, CLOCK);
    }

    @Test
    void createStartsInCreatedWithBothTimestampsSet() {
        Order order = newOrder();

        assertThat(order.getId()).isNotNull();
        assertThat(order.getPartnerId()).isEqualTo("partner-a");
        assertThat(order.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(order.getRequestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(order.getPlanCode()).isEqualTo("BASIC_5G");
        assertThat(order.getSimType()).isEqualTo(SimType.ESIM);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCreatedAt()).isEqualTo(T0);
        assertThat(order.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void msisdnIsStoredWithoutLeadingPlus() {
        assertThat(newOrder().getMsisdn()).isEqualTo("447700900123");
        assertThat(Order.create("partner-a", "key-1", FINGERPRINT, "447700900123", "BASIC_5G", SimType.ESIM, CLOCK).getMsisdn())
                .isEqualTo("447700900123");
    }

    @Test
    void eachOrderGetsItsOwnId() {
        assertThat(newOrder().getId()).isNotEqualTo(newOrder().getId());
    }

    @Test
    void timestampsAreTruncatedToMicrosecondsToMatchTheDatabase() {
        Clock nanoClock = Clock.fixed(Instant.parse("2026-01-01T10:00:00.123456789Z"), ZoneOffset.UTC);

        Order order = Order.create("partner-a", "key-1", FINGERPRINT, "+447700900123", "BASIC_5G", SimType.ESIM, nanoClock);

        assertThat(order.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00.123456Z"));
    }

    @Test
    void legalTransitionChangesStatusAndUpdatedAtOnly() {
        Order order = newOrder();
        Instant later = T0.plusSeconds(5);

        order.transitionTo(OrderStatus.VALIDATED, Clock.fixed(later, ZoneOffset.UTC));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.VALIDATED);
        assertThat(order.getUpdatedAt()).isEqualTo(later);
        assertThat(order.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void illegalTransitionThrowsWithOrderIdAndLeavesOrderUnchanged() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.ACTIVE, CLOCK))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.orderId()).isEqualTo(order.getId());
                    assertThat(ex.from()).isEqualTo(OrderStatus.CREATED);
                    assertThat(ex.to()).isEqualTo(OrderStatus.ACTIVE);
                    assertThat(ex.properties())
                            .containsEntry("orderId", order.getId())
                            .containsEntry("currentStatus", OrderStatus.CREATED)
                            .containsEntry("targetStatus", OrderStatus.ACTIVE);
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getUpdatedAt()).isEqualTo(T0);
    }

    /** An order driven along legal transitions into {@code target}, with all timestamps at T0. */
    private static Order orderIn(OrderStatus target) {
        Order order = newOrder();
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
        assertThat(order.getStatus()).isEqualTo(target);
        return order;
    }

    @ParameterizedTest
    @EnumSource(names = {"CREATED", "VALIDATED"})
    void cancelFromAnEarlyStateCancels(OrderStatus from) {
        Order order = orderIn(from);
        Instant later = T0.plusSeconds(5);

        CancelOutcome outcome = order.cancel(Clock.fixed(later, ZoneOffset.UTC));

        assertThat(outcome).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.pending()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getUpdatedAt()).isEqualTo(later);
        assertThat(order.getCancelRequestedAt()).isNull();
    }

    @Test
    void cancelWhenAlreadyCancelledIsANoOp() {
        Order order = orderIn(OrderStatus.CANCELLED);

        CancelOutcome outcome = order.cancel(Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));

        assertThat(outcome).isEqualTo(CancelOutcome.ALREADY_CANCELLED);
        assertThat(outcome.changed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void cancelWhileProvisioningOnlyRecordsTheRequest() {
        Order order = orderIn(OrderStatus.PROVISIONING);
        Instant later = T0.plusSeconds(5);

        CancelOutcome outcome = order.cancel(Clock.fixed(later, ZoneOffset.UTC));

        assertThat(outcome).isEqualTo(CancelOutcome.CANCEL_REQUESTED);
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.pending()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROVISIONING);
        assertThat(order.isCancelRequested()).isTrue();
        assertThat(order.getCancelRequestedAt()).isEqualTo(later);
        assertThat(order.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void repeatCancelWhileProvisioningKeepsTheFirstRequest() {
        Order order = orderIn(OrderStatus.PROVISIONING);
        order.cancel(CLOCK);

        CancelOutcome outcome = order.cancel(Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));

        assertThat(outcome).isEqualTo(CancelOutcome.ALREADY_REQUESTED);
        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.pending()).isTrue();
        assertThat(order.getCancelRequestedAt()).isEqualTo(T0);
        assertThat(order.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void completeCancellationAfterARequestCancels() {
        Order order = orderIn(OrderStatus.PROVISIONING);
        order.cancel(CLOCK);

        order.completeCancellation(Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void completeCancellationWithoutARequestIsAProgrammingError() {
        Order order = orderIn(OrderStatus.PROVISIONING);

        assertThatThrownBy(() -> order.completeCancellation(CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROVISIONING);
    }

    @ParameterizedTest
    @EnumSource(names = {"CREATED", "VALIDATED", "PROVISIONING"})
    void failRecordsTheReason(OrderStatus from) {
        Order order = orderIn(from);

        order.fail(FailureReason.GATEWAY_REJECTED, Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailureReason()).isEqualTo(FailureReason.GATEWAY_REJECTED);
        assertThat(order.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @ParameterizedTest
    @EnumSource(names = {"ACTIVE", "FAILED", "CANCELLED"})
    void failFromAFinalStateIsAnIllegalTransitionAndKeepsNoReason(OrderStatus from) {
        Order order = orderIn(from);

        assertThatThrownBy(() -> order.fail(FailureReason.GATEWAY_REJECTED, CLOCK))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(order.getFailureReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(names = {"ACTIVE", "FAILED"})
    void cancelFromAFinalStateIsAnIllegalTransition(OrderStatus from) {
        Order order = orderIn(from);

        assertThatThrownBy(() -> order.cancel(CLOCK))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.from()).isEqualTo(from);
                    assertThat(ex.to()).isEqualTo(OrderStatus.CANCELLED);
                });
        assertThat(order.getStatus()).isEqualTo(from);
        assertThat(order.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void transitionToNullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> newOrder().transitionTo(null, CLOCK));
    }
}
