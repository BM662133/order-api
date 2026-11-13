package com.example.orders.order.domain;

import static com.example.orders.order.domain.OrderStatus.ACTIVE;
import static com.example.orders.order.domain.OrderStatus.CANCELLED;
import static com.example.orders.order.domain.OrderStatus.CREATED;
import static com.example.orders.order.domain.OrderStatus.FAILED;
import static com.example.orders.order.domain.OrderStatus.PROVISIONING;
import static com.example.orders.order.domain.OrderStatus.VALIDATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ProblemTypes;

class OrderStatusTest {

    /**
     * The specification, written out independently of the enum. Every pair not listed here must be
     * illegal, so a new constant's pairs are automatically tested as illegal until listed.
     */
    private static final Set<Transition> LEGAL = Set.of(
            new Transition(CREATED, VALIDATED),
            new Transition(CREATED, FAILED),
            new Transition(CREATED, CANCELLED),
            new Transition(VALIDATED, PROVISIONING),
            new Transition(VALIDATED, FAILED),
            new Transition(VALIDATED, CANCELLED),
            new Transition(PROVISIONING, ACTIVE),
            new Transition(PROVISIONING, FAILED),
            new Transition(PROVISIONING, CANCELLED));

    record Transition(OrderStatus from, OrderStatus to) {
    }

    static Stream<Arguments> legalTransitions() {
        return LEGAL.stream().map(t -> Arguments.of(t.from(), t.to()));
    }

    static Stream<Arguments> illegalTransitions() {
        return allPairs().filter(t -> !LEGAL.contains(t)).map(t -> Arguments.of(t.from(), t.to()));
    }

    private static Stream<Transition> allPairs() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> new Transition(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("legalTransitions")
    void legalTransitionIsAllowed(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThatCode(() -> from.checkTransitionTo(to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("illegalTransitions")
    void illegalTransitionIsRejected(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.checkTransitionTo(to))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.from()).isEqualTo(from);
                    assertThat(ex.to()).isEqualTo(to);
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.type()).isEqualTo(ProblemTypes.ILLEGAL_STATUS_TRANSITION);
                    assertThat(ex.properties())
                            .containsEntry("currentStatus", from)
                            .containsEntry("targetStatus", to);
                });
    }

    @Test
    void everyPairIsCoveredExactlyOnce() {
        int n = OrderStatus.values().length;
        assertThat(legalTransitions().count() + illegalTransitions().count()).isEqualTo((long) n * n);
        assertThat(legalTransitions().count()).isEqualTo(9);
        assertThat(illegalTransitions().count()).isEqualTo(27);
    }

    @ParameterizedTest
    @EnumSource(names = {"ACTIVE", "FAILED", "CANCELLED"})
    void terminalStatesHaveNoNextStates(OrderStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedNextStates()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(names = {"CREATED", "VALIDATED", "PROVISIONING"})
    void nonTerminalStatesCanBeCancelled(OrderStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(status.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void allowedNextStatesCannotBeModified() {
        assertThatThrownBy(() -> CREATED.allowedNextStates().add(ACTIVE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(CREATED.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    void nullTargetIsRejected() {
        assertThat(CREATED.canTransitionTo(null)).isFalse();
        assertThatNullPointerException().isThrownBy(() -> CREATED.checkTransitionTo(null));
    }
}
