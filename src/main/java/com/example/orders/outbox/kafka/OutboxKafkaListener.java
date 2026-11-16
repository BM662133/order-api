package com.example.orders.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.orders.outbox.OutboxEventHandlers;

/**
 * Consumes {@value OutboxKafkaCodec#TOPIC} and runs the same handlers the in-process transport calls.
 * The offset is committed after the handler returns (at-least-once); handlers are idempotent, so a
 * redelivery after a rebalance or crash is harmless. Failures go to the {@code DefaultErrorHandler} in
 * {@link KafkaOutboxConfig}.
 */
@Component
@ConditionalOnProperty(name = "orders.outbox.transport", havingValue = "kafka")
class OutboxKafkaListener {

    static final String LISTENER_ID = "outbox-events";
    static final String GROUP_ID = "order-api-provisioning";

    private final OutboxEventHandlers handlers;

    OutboxKafkaListener(OutboxEventHandlers handlers) {
        this.handlers = handlers;
    }

    @KafkaListener(id = LISTENER_ID, groupId = GROUP_ID, topics = OutboxKafkaCodec.TOPIC)
    void onRecord(ConsumerRecord<String, String> record) {
        handlers.dispatch(OutboxKafkaCodec.toEnvelope(record));
    }
}
