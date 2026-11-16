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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.orders.common.error.FieldViolation;
import com.example.orders.common.error.RequestValidationException;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.history.OrderStatusHistoryRepository;
import com.example.orders.order.persistence.IdempotencyRecord;
import com.example.orders.order.persistence.IdempotencyRecordRepository;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

/** The create flow's branches, including the lost-race path that a threaded test can't hit reliably. */
class OrderServiceCreateTest {

    private static final Instant NOW = Instant.parse("2026-01-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PartnerContext PARTNER = new PartnerContext("partner-a");
    private static final String KEY = "key-1";
    private static final String FINGERPRINT = RequestFingerprint.of("+447700900123", "BASIC_5G", SimType.ESIM);
    private static final CreateOrderCommand COMMAND = new CreateOrderCommand("+447700900123", "BASIC_5G", SimType.ESIM);
    private static final Duration TTL = Duration.ofHours(24);

    private final OrderRepository orders = mock(OrderRepository.class);
    private final IdempotencyRecordRepository keys = mock(IdempotencyRecordRepository.class);
    private final OrderWriter writer = mock(OrderWriter.class);
    /** A mock returns no violations unless a test says otherwise. */
    private final Validator validator = mock(Validator.class);
    private final OrderService service = new OrderService(orders, keys, mock(OrderStatusHistoryRepository.class),
            writer, validator, CLOCK);

    /** An order created {@code age} ago with this key. */
    private static Order existing(String fingerprint, Duration age) {
        Clock then = Clock.fixed(NOW.minus(age), ZoneOffset.UTC);
        return Order.create("partner-a", KEY, fingerprint, "+447700900123", "BASIC_5G", SimType.ESIM, then);
    }

    private static Order existing(String fingerprint) {
        return existing(fingerprint, Duration.ofMinutes(5));
    }

    /** Makes the key point at {@code order}, as the key table would. */
    private void keyReserves(Order order) {
        when(keys.findByPartnerIdAndIdempotencyKey("partner-a", KEY))
                .thenReturn(Optional.of(IdempotencyRecord.forOrder(order, TTL)));
        when(orders.findByIdAndPartnerId(order.getId(), "partner-a")).thenReturn(Optional.of(order));
    }

    private CreateResult create() {
        return service.create(PARTNER, KEY, COMMAND);
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        SQLException sql = new SQLException("violation of " + constraintName, "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraintName));
    }

    @Test
    void newKeyInsertsAndIsNotAReplay() {
        when(writer.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateResult result = create();

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.order().getRequestFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void sameKeySameRequestReplaysWithoutInsertingOrValidating() {
        Order order = existing(FINGERPRINT);
        keyReserves(order);

        CreateResult result = create();

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isSameAs(order);
        verifyNoInteractions(writer, validator);
    }

    @Test
    void sameKeyDifferentRequestIs422WithoutInserting() {
        Order order = existing("0".repeat(64));
        keyReserves(order);

        assertThatThrownBy(this::create)
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class,
                        ex -> assertThat(ex.properties()).containsEntry("orderId", order.getId()));
        verifyNoInteractions(writer);
    }

    @Test
    void anExpiredKeyIsTreatedAsUnusedEvenForADifferentRequest() {
        keyReserves(existing("0".repeat(64), TTL.plusMinutes(1)));
        when(writer.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateResult result = create();

        assertThat(result.replayed()).isFalse();
        verify(writer).insert(any());
    }

    @Test
    void lostRaceReturnsTheWinnersOrderAsAReplay() {
        Order winner = existing(FINGERPRINT);
        when(keys.findByPartnerIdAndIdempotencyKey("partner-a", KEY))
                .thenReturn(Optional.empty())                                        // fast path: not committed yet
                .thenReturn(Optional.of(IdempotencyRecord.forOrder(winner, TTL)));  // re-read after the conflict
        when(orders.findByIdAndPartnerId(winner.getId(), "partner-a")).thenReturn(Optional.of(winner));
        when(writer.insert(any())).thenThrow(violationOf("UQ_IDEMPOTENCY_KEYS_PARTNER_KEY_INDEX_8"));

        CreateResult result = create();

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isSameAs(winner);
        verify(keys, times(2)).findByPartnerIdAndIdempotencyKey("partner-a", KEY);
    }

    @Test
    void lostRaceAgainstADifferentRequestIs422() {
        Order winner = existing("0".repeat(64));
        when(keys.findByPartnerIdAndIdempotencyKey("partner-a", KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(IdempotencyRecord.forOrder(winner, TTL)));
        when(orders.findByIdAndPartnerId(winner.getId(), "partner-a")).thenReturn(Optional.of(winner));
        when(writer.insert(any())).thenThrow(violationOf("UQ_IDEMPOTENCY_KEYS_PARTNER_KEY_INDEX_8"));

        assertThatThrownBy(this::create).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void otherIntegrityViolationsAreRethrownWithoutRetrying() {
        DataIntegrityViolationException fkViolation = violationOf("FK_ORDERS_PLAN");
        when(writer.insert(any())).thenThrow(fkViolation);

        assertThatThrownBy(this::create).isSameAs(fkViolation);
        verify(keys, times(1)).findByPartnerIdAndIdempotencyKey("partner-a", KEY);
    }

    @Test
    void conflictWithNoWinnerFoundIsAnInternalError() {
        when(writer.insert(any())).thenThrow(violationOf("UQ_IDEMPOTENCY_KEYS_PARTNER_KEY_INDEX_8"));

        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewRequestFailingTheStatefulChecksIs400WithoutInserting() {
        ConstraintViolation<CreateOrderCommand> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(PathImpl.createPathFromString("planCode"));
        when(violation.getMessage()).thenReturn("must be an active plan");
        when(validator.validate(COMMAND)).thenReturn(Set.of(violation));

        assertThatThrownBy(this::create).isInstanceOfSatisfying(RequestValidationException.class, ex -> {
            assertThat(ex.status().value()).isEqualTo(400);
            assertThat(ex.getMessage()).isEqualTo("Request has 1 invalid field(s).");
            assertThat(ex.properties()).containsEntry("errors",
                    List.of(new FieldViolation("planCode", "must be an active plan")));
        });
        verifyNoInteractions(writer);
    }

    @Test
    void invalidKeyIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create(PARTNER, "has space", COMMAND))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        verifyNoInteractions(orders, keys, writer);
        verify(writer, never()).insert(any());
    }
}
