package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.example.orders.order.domain.CancelOutcome;
import com.example.orders.order.domain.IllegalTransitionException;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.history.OrderStatusHistoryRepository;
import com.example.orders.order.persistence.IdempotencyRecordRepository;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

class OrderServiceCancelTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PartnerContext PARTNER = new PartnerContext("partner-a");
    private static final UUID ORDER_ID = UUID.randomUUID();

    private final OrderWriter writer = mock(OrderWriter.class);
    private final OrderService service = new OrderService(mock(OrderRepository.class),
            mock(IdempotencyRecordRepository.class), mock(OrderStatusHistoryRepository.class), writer,
            mock(Validator.class), CLOCK);

    private static CancelResult cancelled() {
        Order order = Order.create("partner-a", "k", "0".repeat(64), "+447700900123", "BASIC_5G", SimType.ESIM, CLOCK);
        return new CancelResult(order, order.cancel(CLOCK));
    }

    private static ObjectOptimisticLockingFailureException optimisticConflict() {
        return new ObjectOptimisticLockingFailureException(Order.class, ORDER_ID);
    }

    @Test
    void successOnFirstAttemptIsReturned() {
        CancelResult result = cancelled();
        when(writer.cancel("partner-a", ORDER_ID)).thenReturn(result);

        assertThat(service.cancel(PARTNER, ORDER_ID)).isSameAs(result);
        verify(writer, times(1)).cancel("partner-a", ORDER_ID);
    }

    @Test
    void optimisticConflictIsRetriedInAFreshAttempt() {
        CancelResult result = cancelled();
        when(writer.cancel("partner-a", ORDER_ID)).thenThrow(optimisticConflict()).thenReturn(result);

        CancelResult actual = service.cancel(PARTNER, ORDER_ID);

        assertThat(actual.outcome()).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(actual.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(writer, times(2)).cancel("partner-a", ORDER_ID);
    }

    @Test
    void lockAcquisitionFailureIsRetriedToo() {
        CancelResult result = cancelled();
        when(writer.cancel("partner-a", ORDER_ID))
                .thenThrow(new CannotAcquireLockException("row locked"))
                .thenReturn(result);

        assertThat(service.cancel(PARTNER, ORDER_ID)).isSameAs(result);
    }

    @Test
    void persistentConflictBecomesA409AfterMaxAttempts() {
        when(writer.cancel("partner-a", ORDER_ID)).thenThrow(optimisticConflict());

        assertThatThrownBy(() -> service.cancel(PARTNER, ORDER_ID))
                .isInstanceOfSatisfying(OrderConcurrentlyModifiedException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(409);
                    assertThat(ex.properties()).containsEntry("orderId", ORDER_ID);
                    assertThat(ex.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
                });
        verify(writer, times(OrderService.MAX_CANCEL_ATTEMPTS)).cancel("partner-a", ORDER_ID);
    }

    @Test
    void notFoundIsNotRetried() {
        when(writer.cancel("partner-a", ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        assertThatThrownBy(() -> service.cancel(PARTNER, ORDER_ID)).isInstanceOf(OrderNotFoundException.class);
        verify(writer, times(1)).cancel("partner-a", ORDER_ID);
    }

    @Test
    void businessRuleViolationIsNotRetried() {
        when(writer.cancel("partner-a", ORDER_ID))
                .thenThrow(new IllegalTransitionException(ORDER_ID, OrderStatus.ACTIVE, OrderStatus.CANCELLED));

        assertThatThrownBy(() -> service.cancel(PARTNER, ORDER_ID))
                .isInstanceOf(IllegalTransitionException.class);
        verify(writer, times(1)).cancel("partner-a", ORDER_ID);
    }
}
