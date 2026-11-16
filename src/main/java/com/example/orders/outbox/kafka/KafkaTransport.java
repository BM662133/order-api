package com.example.orders.outbox.kafka;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxTransport;

/**
 * Publishes outbox events to {@value OutboxKafkaCodec#TOPIC} and waits for the broker's ack, so the
 * relay marks a row PROCESSED only once the record is durably on the topic. The handler runs later, in
 * {@link OutboxKafkaListener}, with its own retries.
 */
@Component
@ConditionalOnProperty(name = "orders.outbox.transport", havingValue = "kafka")
public class KafkaTransport implements OutboxTransport {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransport.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration sendTimeout;

    public KafkaTransport(KafkaTemplate<String, String> kafkaTemplate,
            @Value("${orders.outbox.kafka.send-timeout:15s}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void deliver(EventEnvelope event) {
        try {
            kafkaTemplate.send(OutboxKafkaCodec.toRecord(event)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("Interrupted publishing event " + event.eventId(), ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new OutboxPublishException("Could not publish event " + event.eventId(), ex);
        }
    }

    /**
     * Publishing failed every time. Unlike a handler failure, this says nothing about the order (the
     * gateway was never tried), so the order is left as it is: set the outbox row back to PENDING to
     * republish once the broker is back.
     */
    @Override
    public void onDead(EventEnvelope event) {
        log.error("Outbox event {} ({}) for {} could not be published to Kafka; left DEAD for republishing",
                event.eventId(), event.eventType(), event.aggregateId());
    }
}
