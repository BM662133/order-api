package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.history.OrderStatusHistoryRepository;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

/** The create flow's branches, including the lost-race path that a threaded test can't hit reliably. */
class OrderServiceCreateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PartnerContext PARTNER = new PartnerContext("partner-a");
    private static final String KEY = "key-1";
    private static final String FINGERPRINT = RequestFingerprint.of("+447700900123", "BASIC_5G", SimType.ESIM);

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderWriter writer = mock(OrderWriter.class);
    private final OrderService service = new OrderService(repository, mock(OrderStatusHistoryRepository.class), writer, CLOCK);

    private static Order existing(String fingerprint) {
        return Order.create("partner-a", KEY, fingerprint, "+447700900123", "BASIC_5G", SimType.ESIM, CLOCK);
    }

    private CreateResult create() {
        return service.create(PARTNER, KEY, "+447700900123", "BASIC_5G", SimType.ESIM);
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        SQLException sql = new SQLException("violation of " + constraintName, "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraintName));
    }

    @Test
    void newKeyInsertsAndIsNotAReplay() {
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY)).thenReturn(Optional.empty());
        when(writer.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateResult result = create();

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.order().getRequestFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void sameKeySameRequestReplaysWithoutInserting() {
        Order order = existing(FINGERPRINT);
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY)).thenReturn(Optional.of(order));

        CreateResult result = create();

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isSameAs(order);
        verifyNoInteractions(writer);
    }

    @Test
    void sameKeyDifferentRequestIs422WithoutInserting() {
        Order order = existing("0".repeat(64));
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY)).thenReturn(Optional.of(order));

        assertThatThrownBy(this::create)
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class,
                        ex -> assertThat(ex.properties()).containsEntry("orderId", order.getId()));
        verifyNoInteractions(writer);
    }

    @Test
    void lostRaceReturnsTheWinnersOrderAsAReplay() {
        Order winner = existing(FINGERPRINT);
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY))
                .thenReturn(Optional.empty())       // fast path: winner not committed yet
                .thenReturn(Optional.of(winner));   // re-read after our insert hit the constraint
        when(writer.insert(any())).thenThrow(violationOf("UQ_ORDERS_PARTNER_IDEMPOTENCY_INDEX_8"));

        CreateResult result = create();

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isSameAs(winner);
        verify(repository, times(2)).findByPartnerIdAndIdempotencyKey("partner-a", KEY);
    }

    @Test
    void lostRaceAgainstADifferentRequestIs422() {
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing("0".repeat(64))));
        when(writer.insert(any())).thenThrow(violationOf("UQ_ORDERS_PARTNER_IDEMPOTENCY_INDEX_8"));

        assertThatThrownBy(this::create).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void otherIntegrityViolationsAreRethrownWithoutRetrying() {
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY)).thenReturn(Optional.empty());
        DataIntegrityViolationException fkViolation = violationOf("FK_ORDERS_PLAN");
        when(writer.insert(any())).thenThrow(fkViolation);

        assertThatThrownBy(this::create).isSameAs(fkViolation);
        verify(repository, times(1)).findByPartnerIdAndIdempotencyKey("partner-a", KEY);
    }

    @Test
    void conflictWithNoWinnerFoundIsAnInternalError() {
        when(repository.findByPartnerIdAndIdempotencyKey("partner-a", KEY)).thenReturn(Optional.empty());
        when(writer.insert(any())).thenThrow(violationOf("UQ_ORDERS_PARTNER_IDEMPOTENCY_INDEX_8"));

        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKeyIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create(PARTNER, "has space", "+447700900123", "BASIC_5G", SimType.ESIM))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        verifyNoInteractions(repository, writer);
        verify(writer, never()).insert(any());
    }
}
