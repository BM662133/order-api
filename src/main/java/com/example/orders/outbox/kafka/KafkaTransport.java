package com.example.orders.outbox.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.example.orders.outbox.DeliveryResult;
import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxTransport;

/**
 * Publishes outbox events to {@value OutboxKafkaCodec#TOPIC}, and reports an event delivered only once
 * the broker has acknowledged it, so the relay marks a row PROCESSED only once the record is durably on
 * the topic. The handler runs later, in {@link OutboxKafkaListener}, with its own retries.
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
        if (deliverAll(List.of(event)).get(0) instanceof DeliveryResult.Failed failed) {
            throw failed.error();
        }
    }

    /**
     * Sends every record without waiting, so the producer can batch them, then waits for all the acks
     * against one overall deadline ({@code send-timeout}). A batch costs one timeout at worst, however
     * many events it holds.
     *
     * <p>If a send fails immediately (typically: the broker is unreachable and fetching topic metadata
     * timed out after {@code max.block.ms}), the rest of the batch would fail the same way, one block
     * each. So the batch stops there and the remaining events are returned as not attempted.
     *
     * <p>Concurrent sends can't reorder events with the same key: the relay never puts two of them in one
     * batch.
     */
    @Override
    public List<DeliveryResult> deliverAll(List<EventEnvelope> events) {
        DeliveryResult[] results = new DeliveryResult[events.size()];
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            EventEnvelope event = events.get(i);
            CompletableFuture<SendResult<String, String>> future;
            try {
                future = kafkaTemplate.send(OutboxKafkaCodec.toRecord(event));
            } catch (RuntimeException ex) {
                future = CompletableFuture.failedFuture(ex);
            }
            if (future.isCompletedExceptionally()) {
                results[i] = DeliveryResult.failed(publishFailure(event, causeOf(future)));
                Arrays.fill(results, i + 1, results.length, DeliveryResult.notAttempted());
                log.warn("Publishing outbox event {} failed immediately; skipping the other {} event(s) in this batch",
                        event.eventId(), results.length - i - 1);
                break;
            }
            futures.add(future);
        }

        long deadline = System.nanoTime() + sendTimeout.toNanos();
        for (int i = 0; i < futures.size(); i++) {
            EventEnvelope event = events.get(i);
            try {
                futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                results[i] = DeliveryResult.delivered();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                // Outcome unknown: report failed so the relay retries (at-least-once; handlers are idempotent).
                for (int j = i; j < futures.size(); j++) {
                    results[j] = DeliveryResult.failed(
                            new OutboxPublishException("Interrupted publishing event " + events.get(j).eventId(), ex));
                }
                break;
            } catch (ExecutionException ex) {
                results[i] = DeliveryResult.failed(publishFailure(event, ex.getCause()));
            } catch (TimeoutException ex) {
                results[i] = DeliveryResult.failed(publishFailure(event, ex));
            }
        }
        return List.of(results);
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

    private static OutboxPublishException publishFailure(EventEnvelope event, Throwable cause) {
        return new OutboxPublishException("Could not publish event " + event.eventId(), cause);
    }

    private static Throwable causeOf(CompletableFuture<?> failed) {
        try {
            failed.join();
            throw new IllegalStateException("Future was not failed");
        } catch (CompletionException ex) {
            return ex.getCause() != null ? ex.getCause() : ex;
        } catch (CancellationException ex) {
            return ex;
        }
    }
}
