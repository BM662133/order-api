package com.example.orders.outbox.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxEventHandlers;

class OutboxDeadLetterRecovererTest {

    private final OutboxEventHandlers handlers = mock(OutboxEventHandlers.class);
    private final ConsumerRecordRecoverer deadLetters = mock(ConsumerRecordRecoverer.class);
    private final OutboxDeadLetterRecoverer recoverer = new OutboxDeadLetterRecoverer(handlers, deadLetters);
    private final RuntimeException failure = new IllegalStateException("gateway down");

    @Test
    void theHandlerIsToldBeforeTheRecordIsDeadLettered() {
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "OrderCreated", UUID.randomUUID(), "partner-a",
                "partner-a:447700900123", "{}");
        ProducerRecord<String, String> sent = OutboxKafkaCodec.toRecord(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
        sent.headers().forEach(header -> record.headers().add(header));

        recoverer.accept(record, failure);

        InOrder order = inOrder(handlers, deadLetters);
        order.verify(handlers).retriesExhausted(event);
        order.verify(deadLetters).accept(record, failure);
    }

    @Test
    void anUnreadableRecordIsStillDeadLettered() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(OutboxKafkaCodec.TOPIC, 0, 0L, "k", "{}");

        recoverer.accept(record, failure);

        verify(handlers, never()).retriesExhausted(any());
        verify(deadLetters).accept(record, failure);
    }
}
