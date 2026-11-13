package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;

/** Proves the classifier against the exceptions H2 + Hibernate actually throw, not hand-built ones. */
@SpringBootTest
class IdempotencyConflictsTest {

    @Autowired
    private OrderWriter writer;

    @Autowired
    private Clock clock;

    private Order order(String partner, String key, String planCode) {
        return Order.create(partner, key, "0".repeat(64), "+447700900123", planCode, SimType.ESIM, clock);
    }

    @Test
    void duplicateKeyForSamePartnerIsRecognised() {
        String key = UUID.randomUUID().toString();
        writer.insert(order("partner-a", key, "BASIC_5G"));

        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> writer.insert(order("partner-a", key, "BASIC_5G")));

        assertThat(ex).isNotNull();
        assertThat(IdempotencyConflicts.isKeyConflict(ex)).isTrue();
    }

    @Test
    void foreignKeyViolationIsNotMistakenForAKeyConflict() {
        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> writer.insert(order("partner-a", UUID.randomUUID().toString(), "NO_SUCH_PLAN")));

        assertThat(ex).isNotNull();
        assertThat(IdempotencyConflicts.isKeyConflict(ex)).isFalse();
    }

    @Test
    void sameKeyForDifferentPartnersIsAllowed() {
        String key = UUID.randomUUID().toString();
        writer.insert(order("partner-a", key, "BASIC_5G"));

        assertThatCode(() -> writer.insert(order("partner-b", key, "BASIC_5G"))).doesNotThrowAnyException();
    }
}
