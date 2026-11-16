package com.example.orders.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Delivers due outbox events to their {@link OutboxEventHandler}, at least once. Deliberately not
 * transactional: handlers run their own short transactions and may do network I/O in between.
 *
 * <p>In-process for now; with Kafka (slice 7) this would publish to a topic and a listener would call
 * the same handlers.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final Map<String, OutboxEventHandler> handlers;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outboxRepository, List<OutboxEventHandler> handlers,
            OutboxProperties properties, Clock clock) {
        this.outboxRepository = outboxRepository;
        // toMap fails fast on two handlers for one event type.
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Delivers one batch of due events, oldest first. A failure is recorded on its event and does not
     * stop the batch.
     *
     * @return how many events were attempted
     */
    public int relayDue() {
        List<OutboxEvent> due = outboxRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, clock.instant(), Limit.of(properties.batchSize()));
        due.forEach(this::deliver);
        return due.size();
    }

    private void deliver(OutboxEvent event) {
        OutboxEventHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            event.recordFailure("No handler for event type " + event.getEventType(), Duration.ZERO, clock);
            event.markDead();
            outboxRepository.save(event);
            log.error("Outbox event {} has unknown type {}; marked DEAD", event.getId(), event.getEventType());
            return;
        }
        try {
            handler.handle(event);
        } catch (RuntimeException ex) {
            recordFailure(event, handler, ex);
            return;
        }
        event.markProcessed(clock);
        outboxRepository.save(event);
    }

    private void recordFailure(OutboxEvent event, OutboxEventHandler handler, RuntimeException ex) {
        event.recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                properties.backoffAfter(event.getAttempts() + 1), clock);
        if (event.getAttempts() < properties.maxAttempts()) {
            outboxRepository.save(event);
            log.warn("Outbox event {} ({}) failed, attempt {}/{}; retrying at {}: {}", event.getId(),
                    event.getEventType(), event.getAttempts(), properties.maxAttempts(), event.getNextAttemptAt(),
                    ex.toString());
            return;
        }
        event.markDead();
        outboxRepository.save(event);
        log.error("Outbox event {} ({}) failed {} times; marked DEAD", event.getId(), event.getEventType(),
                event.getAttempts(), ex);
        try {
            handler.onRetriesExhausted(event);
        } catch (RuntimeException callbackFailure) {
            log.error("onRetriesExhausted failed for outbox event {}", event.getId(), callbackFailure);
        }
    }
}
