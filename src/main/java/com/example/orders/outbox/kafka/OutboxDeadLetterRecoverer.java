package com.example.orders.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import com.example.orders.outbox.OutboxEventHandlers;

/**
 * Runs when a record has failed every retry (or failed with a non-retryable error): tells the event's
 * handler first (the provisioning worker fails the order, as the in-process relay would), then hands
 * the record to the dead-letter publisher.
 */
class OutboxDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeadLetterRecoverer.class);

    private final OutboxEventHandlers handlers;
    private final ConsumerRecordRecoverer deadLetterPublisher;

    OutboxDeadLetterRecoverer(OutboxEventHandlers handlers, ConsumerRecordRecoverer deadLetterPublisher) {
        this.handlers = handlers;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        try {
            @SuppressWarnings("unchecked")
            ConsumerRecord<String, String> stringRecord = (ConsumerRecord<String, String>) record;
            handlers.retriesExhausted(OutboxKafkaCodec.toEnvelope(stringRecord));
        } catch (RuntimeException ex) {
            log.error("Could not run retries-exhausted handling for record {}-{}@{}", record.topic(),
                    record.partition(), record.offset(), ex);
        }
        log.error("Outbox record {}-{}@{} failed; sending to {}", record.topic(), record.partition(), record.offset(),
                OutboxKafkaCodec.DEAD_LETTER_TOPIC, exception);
        deadLetterPublisher.accept(record, exception);
    }
}
