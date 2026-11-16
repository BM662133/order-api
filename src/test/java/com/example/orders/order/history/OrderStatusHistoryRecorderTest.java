package com.example.orders.order.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.OrderStatusChanged;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.support.StatusHistory;

/** The Spring Data domain-event wiring itself, below the API. */
@SpringBootTest
class OrderStatusHistoryRecorderTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEventPublisher events;

    private Order newOrder() {
        return Order.create("partner-a", UUID.randomUUID().toString(), "0".repeat(64), "+447700900123", "BASIC_5G",
                SimType.ESIM, clock);
    }

    @Test
    void savingTheSameInstanceTwicePublishesEachChangeOnce() {
        Order order = newOrder();

        transactionTemplate.executeWithoutResult(tx -> {
            orderRepository.saveAndFlush(order);
            order.transitionTo(OrderStatus.VALIDATED, clock);
            orderRepository.saveAndFlush(order);
        });

        assertThat(order.statusChanges()).as("cleared after publication").isEmpty();
        assertThat(StatusHistory.timeline(jdbc, order.getId().toString()))
                .containsExactly("null->CREATED by CLIENT", "CREATED->VALIDATED by SYSTEM");
    }

    @Test
    void aBareSaveRecordsHistoryInTheRepositorysOwnTransaction() {
        Order order = newOrder();

        orderRepository.saveAndFlush(order);

        assertThat(StatusHistory.timeline(jdbc, order.getId().toString())).containsExactly("null->CREATED by CLIENT");
    }

    @Test
    void aChangePublishedWithoutATransactionIsRejected() {
        OrderStatusChanged change = newOrder().statusChanges().get(0);

        assertThatThrownBy(() -> events.publishEvent(change)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
