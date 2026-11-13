package com.example.orders.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

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

    @Test
    void transitionToNullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> newOrder().transitionTo(null, CLOCK));
    }
}
