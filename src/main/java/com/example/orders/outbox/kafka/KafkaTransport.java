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

import org.apache.kafka.common.errors.RetriableException;
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
        switch (deliverAll(List.of(event)).get(0)) {
            case DeliveryResult.Failed failed -> throw failed.error();
            case DeliveryResult.Unavailable unavailable -> throw unavailable.error();
            default -> {
            }
        }
    }

    /**
     * Sends every record without waiting, so the producer can batch them, then waits for all the acks
     * against one overall deadline ({@code send-timeout}). A batch costs one timeout at worst, however
     * many events it holds.
     *
     * <p>A failure that means the broker didn't take the record in time is reported as
     * {@link DeliveryResult.Unavailable} (see {@link #brokerUnavailable}), so the relay pauses instead of
     * spending attempts. Anything else, such as a record that is too large, is {@link DeliveryResult.Failed}
     * and counts an attempt for that event only.
     *
     * <p>If a send is unavailable immediately (typically: fetching topic metadata timed out after
     * {@code max.block.ms}), the rest of the batch would block the same way, one {@code max.block.ms} each.
     * So the batch stops there and the remaining events are returned as not attempted. An immediate
     * {@code Failed} doesn't stop the batch: one bad record says nothing about the others.
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
            futures.add(future);
            if (future.isCompletedExceptionally()) {
                results[i] = failure(event, causeOf(future));
                if (results[i] instanceof DeliveryResult.Unavailable) {
                    Arrays.fill(results, i + 1, results.length, DeliveryResult.notAttempted());
                    log.warn("Kafka unavailable publishing outbox event {}; skipping the other {} event(s) in "
                            + "this batch", event.eventId(), results.length - i - 1);
                    break;
                }
            }
        }

        long deadline = System.nanoTime() + sendTimeout.toNanos();
        for (int i = 0; i < futures.size(); i++) {
            if (results[i] != null) {
                continue;
            }
            EventEnvelope event = events.get(i);
            try {
                futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                results[i] = DeliveryResult.delivered();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                // Outcome unknown: report failed so the relay retries (at-least-once; handlers are idempotent).
                for (int j = i; j < futures.size(); j++) {
                    if (results[j] == null) {
                        results[j] = DeliveryResult.failed(new OutboxPublishException(
                                "Interrupted publishing event " + events.get(j).eventId(), ex));
                    }
                }
                break;
            } catch (ExecutionException ex) {
                results[i] = failure(event, ex.getCause());
            } catch (TimeoutException ex) {
                results[i] = failure(event, ex);
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

    private static DeliveryResult failure(EventEnvelope event, Throwable cause) {
        OutboxPublishException error = new OutboxPublishException("Could not publish event " + event.eventId(),
                cause);
        return brokerUnavailable(cause) ? DeliveryResult.unavailable(error) : DeliveryResult.failed(error);
    }

    /**
     * Whether the failure means the broker didn't take the record in time, rather than anything about the
     * record: a Kafka {@link RetriableException} (metadata or delivery timeout, network and leader errors,
     * all already retried by the producer up to {@code delivery.timeout.ms}), or our own
     * {@code send-timeout} running out.
     */
    static boolean brokerUnavailable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RetriableException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
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
