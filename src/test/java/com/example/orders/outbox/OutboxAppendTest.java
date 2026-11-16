package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest
class OutboxAppendTest {

    @Autowired
    private Outbox outbox;

    @Test
    void appendingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outbox.append(UUID.randomUUID(), "partner-a", "TestEvent", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
